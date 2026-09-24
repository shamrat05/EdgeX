import org.gradle.api.JavaVersion

object Configs {
    const val compileSdk = 36
    const val minSdk = 35
    const val targetSdk = 36

    const val namespace = "com.fan.edgex"
    const val applicationId = "com.fan.edgex"
    const val versionCode = 212
    const val versionName = "2.1.2"

    val javaVersion = JavaVersion.VERSION_11
    const val jvmTarget = "11"
}
