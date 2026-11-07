# AirPlus Coding Standards

Everyone is invited to participate in the development of AirPlus with pull requests and to open issues on our [issue tracker](https://github.com/lmx0721/AirPlus/issues). However, we have to enforce certain standards to keep our code readable, consistent and easier to maintain.

We kindly ask you to use [Kotlin](https://kotlinlang.org/) instead of Java for new code, if possible. In the long term, it is our goal to largely migrate the client to Kotlin.

AirPlus is derived from [LiquidBounce Legacy](https://github.com/CCBlueX/LiquidBounce/tree/legacy) and is licensed under GPL-3.0. Upstream attribution must be preserved in every file (see [Files](#files)).

## General

This section lists the official conventions of the languages Kotlin and Java. This project tries to follow them as closely as possible and we expect outside developers to do the same when working on the client.

**Additional, non-standard conventions are listed below. These must also be followed.**

### Kotlin

* Follow Kotlin's official [code conventions](https://kotlinlang.org/docs/reference/coding-conventions.html#coding-conventions).
* Have a look at Kotlin's official [documentation](https://kotlinlang.org/docs/reference/).

### Java

* Have a look at Oracle's [Java Code PDF document](https://www.oracle.com/technetwork/java/codeconventions-150003.pdf).
* Read the Wikipedia article on [Java's Syntax](https://en.wikipedia.org/wiki/Java_syntax).
* Look at Oracle's [Java Tutorial](https://docs.oracle.com/javase/tutorial/java/).

# Rewriting

If parts of the codebase that are currently still written in Java can be ported to Kotlin without changing its behaviour, you are welcome to do so. However, please do not simply rely on IntelliJ's auto-conversion feature, but improve the generated code if necessary.

# Files

### Generation

To document the ownership of a file and to keep the GPL-3.0 attribution chain intact, every code file *(.kt and .java)* must start with the following header comment:

```kotlin
/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Derived from LiquidBounce Legacy (https://github.com/CCBlueX/LiquidBounce), GPL-3.0.
 */
```

Notes:

* The header goes **above** the `package` declaration and above any `@file:` annotations.
* Keep the wording identical across files — no re-branding, no added slogans, no per-file date or version.
* Only code files need it. `.json`, `.cfg`, `.frag`, `.vert` and other resources do not.
* Vendored or ported third-party code (`music/.../jflac`, `viaversion`, `nashorn`, ports of other clients) **keeps its original license header**. Do not overwrite it. If the file was adapted for AirPlus, append one line below the original header:

  ```kotlin
  /*
   * Ported / adapted for AirPlus. See https://github.com/lmx0721/AirPlus
   */
  ```

### Tags

`@author <author-name>` tags are allowed, but minimize its usage as much as possible.

No other tags are allowed.

# Packages

### Naming

Our naming of packages follows the following format:

* `country.company-name.product-name`

*Example:*

* `net.airplus`

If your code is self-contained and not designed exclusively for AirPlus, we may allow you to include it in a separate package outside `net.airplus`. Please note that we have to decide on a case by case basis.

*Example:*
`net.vitox` instead of `net.airplus`

Links:

* [Java Package](https://en.wikipedia.org/wiki/Java_package "Wikipedia article").

# Paths

### No absolute paths

**Never hardcode an absolute path in source code, build scripts or committed configuration.** A path that works on your machine is a bug on everyone else's.

Forbidden:

* Drive letters or root anchors: `C:\Users\...`, `D:/AirPlus/...`, `/home/user/...`
* Literal environment expansions: `%APPDATA%\...`, `$HOME/...`
* A path assembled from a hardcoded prefix, e.g. `"C:/mc/config/" + name`
* Committing machine-specific values into `gradle.properties`, e.g. `org.gradle.java.home=C:\\Program Files\\Java\\jdk-17`

Required instead:

* **Runtime files** — derive from the game directory, never from a literal:
  ```kotlin
  // Bad
  val f = File("C:/Users/me/AppData/Roaming/.minecraft/config/airplus/values.json")

  // Good
  val f = File(File(Minecraft.getMinecraft().mcDataDir, "config/airplus"), "values.json")
  ```
  Use `File(parent, child)` or `Paths.get(base).resolve(child)`. Do not build paths with string concatenation.
* **Bundled resources** — read them from the classpath, not from disk:
  ```kotlin
  // Bad
  val stream = FileInputStream("src/main/resources/assets/minecraft/airplus/blur.json")

  // Good
  val stream = AirPlus::class.java.getResourceAsStream("/assets/minecraft/airplus/blur.json")
  ```
  In-game assets go through `ResourceLocation("airplus/...")` and the resource manager.
* **Build scripts** — keep machine-specific state out of the repository. Use the Java toolchain, `JAVA_HOME`, or a user-level `~/.gradle/gradle.properties` that is never committed.
* **Logs and error messages** — the path is part of the message only if it is computed at runtime (`LOGGER.error("Failed to write $file")`), never a literal.
* **Settings files shipped with the client** — store relative keys, resolve them against the active root on load.

The only place a literal path may appear is documentation and comments as an illustrative example. It must never be a path the code actually reads or writes.
