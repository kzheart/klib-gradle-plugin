package me.kzheart.klib.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** All dependency traffic is local: no public repository or externally supplied failure is required. */
class DependencyAvailabilityTest {
    @TempDir static Path testKit;
    @TempDir Path directory;

    @Test void cachedDependenciesRebuildOfflineWithoutRepositoryRequests() throws Exception {
        try (MavenServer server = new MavenServer()) {
            Path warm = project("warm", server, "1.0");
            BuildResult online = runner(warm).build();
            assertEquals(TaskOutcome.SUCCESS, online.task(":shadowJar").getOutcome());
            int requests = server.requests.get();
            assertTrue(requests >= 2, "POM and artifact must have been fetched before testing offline mode");
            assertTrue(server.paths.stream().anyMatch(path -> path.endsWith("/probe-1.0.pom")));
            assertTrue(server.paths.stream().anyMatch(path -> path.endsWith("/probe-1.0.jar")));
            server.status = 503;
            // A distinct project has neither task outputs nor a configuration-cache entry.
            Path offline = project("offline-hit", server, "1.0");
            BuildResult cached = runner(offline, "--offline").build();
            assertEquals(TaskOutcome.SUCCESS, cached.task(":assembleKlibCandidate").getOutcome());
            assertEquals(requests, server.requests.get(), "--offline must not consult the repository");
            try (ZipFile jar = new ZipFile(output(offline).toFile())) {
                assertNotNull(jar.getEntry("fixture/network/payload.txt"));
            }
            evidence("offline-cache-hit", cached, server, "repositoryRequestsAfterWarmup=" + requests);
        }
    }

    @Test void missingOfflineDependencyFailsWithoutRepositoryRequestsOrFinalJar() throws Exception {
        try (MavenServer server = new MavenServer()) {
            Path project = project("offline-miss", server, "missing-" + UUID.randomUUID());
            BuildResult result = runner(project, "--offline").buildAndFail();
            assertTrue(result.getOutput().contains("available for offline mode"), result.getOutput());
            assertTrue(result.getOutput().contains(server.group + ":probe:"), result.getOutput());
            assertEquals(0, server.requests.get());
            assertFalse(Files.exists(output(project)));
            evidence("offline-cache-miss", result, server, "finalJar=false");
        }
    }

    @Test void unavailableDependencyReportsHttp503AndPublishesNothing() throws Exception {
        try (MavenServer server = new MavenServer()) {
            server.status = 503;
            Path project = project("unavailable", server, "1.0");
            BuildResult result = runner(project).buildAndFail();
            assertTrue(server.requests.get() > 0);
            assertTrue(server.paths.stream().allMatch(path -> path.startsWith("/" + server.group.replace('.', '/') + "/probe/")));
            assertTrue(result.getOutput().contains("503"), result.getOutput());
            assertTrue(result.getOutput().contains(server.group + ":probe:"), result.getOutput());
            assertFalse(Files.exists(output(project)));
            evidence("http-503", result, server, "finalJar=false");
        }
    }

    @Test void slowDependencyHasAnExplicitSocketTimeoutAndPublishesNothing() throws Exception {
        try (MavenServer server = new MavenServer()) {
            server.delayMillis = 5000;
            Path project = project("slow", server, "1.0");
            long started = System.nanoTime();
            BuildResult result = runner(project,
                    "-Dorg.gradle.internal.http.connectionTimeout=1000",
                    "-Dorg.gradle.internal.http.socketTimeout=250").buildAndFail();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(server.requests.get() > 0);
            assertTrue(server.paths.stream().allMatch(path -> path.startsWith("/" + server.group.replace('.', '/') + "/probe/")));
            assertTrue(result.getOutput().contains("Read timed out"), result.getOutput());
            assertTrue(result.getOutput().contains(server.group + ":probe:"), result.getOutput());
            assertFalse(Files.exists(output(project)));
            evidence("slow-read-timeout", result, server,
                    "configuredSocketTimeoutMillis=250; serverDelayMillis=5000; buildElapsedMillis=" + elapsedMillis);
        }
    }

    private Path project(String name, MavenServer server, String version) throws Exception {
        Path project = Files.createDirectories(directory.resolve(name));
        Path stdlib = Files.createDirectories(project.resolve("stdlib/org/jetbrains/kotlin/kotlin-stdlib/"
                + KlibPlugin.KOTLIN_VERSION));
        String stem = "kotlin-stdlib-" + KlibPlugin.KOTLIN_VERSION;
        Files.copy(Paths.get(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                stdlib.resolve(stem + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.write(stdlib.resolve(stem + ".pom"), pom("org.jetbrains.kotlin", "kotlin-stdlib", KlibPlugin.KOTLIN_VERSION));
        Files.write(project.resolve("settings.gradle"), "rootProject.name='network-fixture'\n".getBytes(StandardCharsets.UTF_8));
        String build = "plugins { id 'me.kzheart.klib' }\nversion='1.0'\n"
                + "repositories { maven { url=uri('stdlib'); content { includeGroup 'org.jetbrains.kotlin' } }; "
                + "exclusiveContent { forRepository { maven { url='http://127.0.0.1:" + server.port()
                + "'; allowInsecureProtocol=true } }; filter { includeGroup '" + server.group + "' } } }\n"
                + "klib { main('example.Main'); targetPackage('example'); modules { none() } }\n"
                + "dependencies { klibEmbedded '" + server.group + ":probe:" + version + "' }\n";
        Files.write(project.resolve("build.gradle"), build.getBytes(StandardCharsets.UTF_8));
        return project;
    }

    private static GradleRunner runner(Path project, String... arguments) {
        List<String> args = new ArrayList<String>(Arrays.asList("shadowJar", "--stacktrace", "--no-configuration-cache", "--no-build-cache",
                "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process",
                "-Dhttp.nonProxyHosts=localhost|127.*|[::1]", "-Dhttps.nonProxyHosts=localhost|127.*|[::1]"));
        args.addAll(Arrays.asList(arguments));
        return GradleRunner.create().withProjectDir(project.toFile()).withTestKitDir(testKit.toFile())
                .withPluginClasspath().withArguments(args);
    }

    private static Path output(Path project) { return project.resolve("build/libs/network-fixture-1.0-all.jar"); }
    private static byte[] pom(String group, String artifact, String version) {
        return ("<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                + artifact + "</artifactId><version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8);
    }
    private static void evidence(String name, BuildResult result, MavenServer server, String details) throws IOException {
        Path evidence = Files.createDirectories(Paths.get("build/dependency-availability-evidence"));
        Files.write(evidence.resolve(name + ".log"), ("repositoryRequests=" + server.requests.get() + "; " + details
                + "\nrequestedPaths=" + server.paths + "\n" + result.getOutput()).getBytes(StandardCharsets.UTF_8));
    }

    private static final class MavenServer implements AutoCloseable {
        final String group = "fixture.network.n" + UUID.randomUUID().toString().replace("-", "");
        final AtomicInteger requests = new AtomicInteger();
        final java.util.Queue<String> paths = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        final ServerSocket listener;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final byte[] jar;
        volatile int status = 200;
        volatile long delayMillis;
        volatile boolean closed;

        MavenServer() throws IOException {
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("fixture/network/payload.txt"));
                zip.write("cached payload".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            jar = bytes.toByteArray();
            workers.execute(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept();
                        workers.execute(() -> serve(socket));
                    } catch (IOException failure) {
                        if (!closed) throw new IllegalStateException(failure);
                    }
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        private void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine();
                if (request == null) return;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { /* consume HTTP headers */ }
                String path = request.split(" ")[1];
                paths.add(path);
                requests.incrementAndGet();
                if (delayMillis > 0) Thread.sleep(delayMillis);
                int response = status;
                byte[] payload = path.endsWith(".pom") ? pom(group, "probe", "1.0") : jar;
                if (!path.endsWith(".pom") && !path.endsWith(".jar")) response = 404;
                if (response != 200) payload = "fixture repository unavailable".getBytes(StandardCharsets.US_ASCII);
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 " + response + (response == 200 ? " OK" : " Unavailable")
                        + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                if (!request.startsWith("HEAD ")) output.write(payload);
                output.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedWhenClientTimesOut) {
                // The timeout fixture intentionally abandons its request before the response is sent.
            }
        }
        @Override public void close() throws IOException {
            closed = true;
            listener.close();
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(10, TimeUnit.SECONDS)) throw new IOException("Fixture server did not stop");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping fixture server", interrupted);
            }
        }
    }
}
