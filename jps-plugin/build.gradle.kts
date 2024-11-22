plugins {
  id("org.jetbrains.intellij.platform.module")
}
dependencies {
  implementation(project(":common"))
}
java {
    sourceCompatibility = JavaVersion.VERSION_11  // Örneğin Java 11
    targetCompatibility = JavaVersion.VERSION_11  // Örneğin Java 11
}

