plugins {
    java
}

group = "org.manator"
version = "1.0.0"

repositories {
    mavenCentral() // Use Maven Central for Velocity API
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.1.1") // Update to a valid version
    annotationProcessor("com.velocitypowered:velocity-api:3.1.1")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17 // Ensure compatibility with Velocity 3.4+
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
    archiveBaseName.set("velocity-autostart")
}
