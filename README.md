# Bukkit Implementation
This is the Bukkit implementation of Pathetic. It provides a simple API to use the Pathetic engine in your Bukkit plugin.

Currently Paper and Spigot are explicitly supported, every other server software is a lucky hit.


*This repository was previously part of the [Pathetic repository](https://github.com/bsommerfeld/pathetic).*

## How to use it?
To integrate pathetic into your bukkit project, follow the following steps:
<br>
*(We advise you to relocate pathetic)*

<h3>Maven</h3>

```xml

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.bsommerfeld.pathetic-bukkit</groupId>
    <artifactId>core</artifactId>
    <version>VERSION</version>
</dependency>
```

<h3>Gradle</h3>

```groovy
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.bsommerfeld.pathetic-bukkit:core:VERSION'
}
```

<h2>Example Usage</h2>

<p>See the <a href="https://github.com/bsommerfeld/pathetic-bukkit/tree/trunk/example">example</a> module for a detailed example.</p>
