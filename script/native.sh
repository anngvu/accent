export DTLV_COMPILE_NATIVE=true
export USE_NATIVE_IMAGE_JAVA_PLATFORM_MODULE_SYSTEM=false
export GRAALVM_HOME="$HOME/graalvm-ce-java17-22.3.1"

# --verbose \
"$GRAALVM_HOME/bin/native-image" \
     --static \
     --features=InitAtBuildTimeFeature \
     --libc=musl \
     -H:CCompilerOption=-Wl,-z,stack-size=2097152 \
     -H:Optimize=2 \
     -jar ./target/uberjar/accent-0.4.0-standalone.jar

