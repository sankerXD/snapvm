plugins {
    java
    `maven-publish`
}

group = "org.snapvm"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Guest-side CLDC classes (Vector/Hashtable/StringBuffer/...): compiled to
// .class and shipped as jar resources under cldc/; Vm registers them as a
// ClassSource so container calls run as guest bytecode instead of host calls.
val compileCldc = tasks.register<JavaCompile>("compileCldc") {
    source = fileTree("src/main/cldc").matching { include("**/*.java") }
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("cldc"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}

tasks.processResources {
    // .classdata not .class: Android packaging strips .class files from
    // library jar resources — renamed files sail through mergeJavaRes.
    from(compileCldc) {
        into("cldc")
        include("**/*.class")
        eachFile { path = path.removeSuffix(".class") + ".classdata" }
    }
}

tasks.test {
    testLogging.showStandardStreams = true
}
