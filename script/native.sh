export DTLV_COMPILE_NATIVE=true
export USE_NATIVE_IMAGE_JAVA_PLATFORM_MODULE_SYSTEM=false

"$GRAALVM_HOME/bin/native-image" \
     --verbose \
     --static \
     --features=InitAtBuildTimeFeature \
     --libc=musl \
     -H:CCompilerOption=-Wl,-z,stack-size=2097152 \
     -jar ./target/uberjar/accent-0.1.0-SNAPSHOT-standalone.jar

