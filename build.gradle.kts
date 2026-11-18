plugins {
    java
}

group = "org.manator"
version = "1.2.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.3.2-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.2-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.jar {
    archiveBaseName.set("velocity-autostart")
}
