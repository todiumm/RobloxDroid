import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val prepareRootfs by tasks.registering {
    val manifest = rootProject.file("rootfs.properties")
    val archive = file("src/main/assets/rootfs.tar.xz")
    inputs.file(manifest)
    outputs.file(archive)
    // An interrupted/manual download must not be considered a valid asset.
    outputs.upToDateWhen { false }
    doLast {
        val config = Properties().apply { manifest.inputStream().use { load(it) } }
        val expected = config.getProperty("sha256")
        val url = config.getProperty("url")
        require(expected.matches(Regex("[a-f0-9]{64}")) && url.startsWith("https://"))
        fun valid(f: File): Boolean {
            if (!f.isFile) return false
            val digest = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == expected
        }
        if (!valid(archive)) {
            archive.parentFile.mkdirs()
            val temp = File.createTempFile(".rootfs-", ".part", archive.parentFile)
            try {
                logger.lifecycle("Baixando rootfs (~119 MB)…")
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 30000
                    readTimeout = 120000
                }.getInputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
                check(valid(temp)) { "SHA-256 do rootfs incorreto; tente novamente." }
                Files.move(temp.toPath(), archive.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareRootfs)
    doFirst {
        val required = listOf(
            "src/main/jniLibs/arm64-v8a/libbox64.so",
            "src/main/jniLibs/arm64-v8a/libXlorie.so",
            "src/main/assets/glibc-x86_64/libX11.so.6",
            "src/main/assets/glibc-x86_64/libpulse.so.0",
            "src/main/assets/glibc-x86_64/libpulse-simple.so.0",
            "src/main/assets/glibc-x86_64/libdbus-1.so.3",
            "src/main/assets/x86_64-libs/libctype_fix.so",
            "src/main/assets/x86_64-libs/libgodot_ctype_patch.so",
            "src/main/assets/x86_64-libs/libpthread_recursive_fix.so",
            "src/main/assets/x86_64-libs/libeaccess_shim.so",
        )
        for (path in required) {
            if (!file(path).exists()) error("Missing pre-built: $path")
        }
    }
}

android {
    namespace = "com.robloxdroid.studio"
    compileSdk { version = release(36) }

    defaultConfig {
        applicationId = "com.robloxdroid.studio"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.9.11-rootfs-fixes"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources { noCompress += listOf("tar.gz", "tar.xz", "txz") }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
