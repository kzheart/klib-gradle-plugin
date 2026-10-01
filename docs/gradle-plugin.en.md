# Gradle plugin

Version 0.6.1 fixes single-segment package relocation such as `kotlin`, keeping JVM and dotted separators distinct and avoiding a second replacement of the destination. To bundle the Kotlin standard library, declare it in `klibEmbedded` and configure `relocate("kotlin", "kotlin")`. Apply the Kotlin compiler plugin separately in the consuming project.

See the [English guide](README.md). Select PostgreSQL with `data { postgresql() }`; the driver and JDBC service metadata are relocated together.
