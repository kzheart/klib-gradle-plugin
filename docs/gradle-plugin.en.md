# Gradle plugin

Version 0.6.1 fixes single-segment package relocation such as `kotlin`, keeping JVM and dotted separators distinct and avoiding a second replacement of the destination. To bundle the Kotlin standard library, declare it in `klibEmbedded` and configure `relocate("kotlin", "kotlin")`. Apply the Kotlin compiler plugin separately in the consuming project.

See the [English guide](README.md). Select PostgreSQL with `data { postgresql() }`; the driver and JDBC service metadata are relocated together.

## External runtime libraries

`klib { externalLibrary("org.postgresql:postgresql:<pgjdbc-version>", "org.postgresql") }` declares a fixed Maven coordinate in Bukkit/Paper `plugin.yml` libraries, excludes that artifact from the bundle, and leaves the package unrelocated. Requires a server library loader; prepare its cache before deployment. Klib itself stays embedded and isolated. Velocity and Guard products must supply libraries through their host instead.
