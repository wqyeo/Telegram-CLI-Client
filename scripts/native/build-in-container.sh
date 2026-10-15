#!/usr/bin/env bash
set -euo pipefail
: "${REVISION:?}" "${SOURCE_SHA256:?}" "${BUILDER_IMAGE:?}"
JOBS=${JOBS:-2}
[[ $JOBS =~ ^[1-4]$ ]] || { echo 'JOBS must be 1 through 4.' >&2; exit 2; }
printf '%s  %s\n' "$SOURCE_SHA256" "/work/downloads/tdlib-$REVISION.tar.gz" | sha256sum --check --status
source_dir=/work/tdlib-$REVISION
if [[ ! -d $source_dir ]]; then
    mkdir "$source_dir"
    tar --extract --gzip --file "/work/downloads/tdlib-$REVISION.tar.gz" --directory "$source_dir" --strip-components=1
fi
cmake '-UZLIB_*' -S "$source_dir" -B "$source_dir/jnibuild" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release '-DCMAKE_CXX_FLAGS_RELEASE=-O1 -DNDEBUG' \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DTD_ENABLE_JNI=ON \
    -DOPENSSL_USE_STATIC_LIBS=TRUE -DZLIB_USE_STATIC_LIBS=FALSE \
    -DCMAKE_INSTALL_PREFIX="$source_dir/example/java/td"
cmake --build "$source_dir/jnibuild" --target install --parallel "$JOBS"
cmake -S "$source_dir/example/java" -B "$source_dir/example/java/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DTd_DIR="$source_dir/example/java/td/lib/cmake/Td" \
    -DCMAKE_INSTALL_PREFIX="$source_dir/example/java" '-DCMAKE_INSTALL_RPATH=$ORIGIN' \
    -DCMAKE_SHARED_LINKER_FLAGS='-static-libgcc -static-libstdc++'
cmake --build "$source_dir/example/java/build" --target install --parallel "$JOBS"
bundle=/work/bundles/linux-x86_64
mkdir -p "$bundle/licenses" "$bundle/sources" /work/sdk-classes
cp "$source_dir/example/java/bin/libtdjni.so" "$bundle/libtdjni.so"
# Ubuntu's static zlib is not PIC. Ship its shared ABI with an $ORIGIN runpath.
cp -L /usr/lib/x86_64-linux-gnu/libz.so.1 "$bundle/libz.so.1"
cp "$source_dir/example/java/org/drinkless/tdlib/Client.java" "$bundle/sources/Client.java"
cp "$source_dir/example/java/org/drinkless/tdlib/TdApi.java" "$bundle/sources/TdApi.java"
javac --release 25 -encoding UTF-8 -d /work/sdk-classes "$bundle/sources/Client.java" "$bundle/sources/TdApi.java"
jar --create --file "$bundle/tdlib-java.jar" --date=2026-10-15T00:00:00Z -C /work/sdk-classes org
cp "$source_dir/LICENSE_1_0.txt" "$bundle/licenses/TDLib-LICENSE"
cp /usr/share/doc/libssl-dev/copyright "$bundle/licenses/OpenSSL-copyright"
cp /usr/share/doc/zlib1g-dev/copyright "$bundle/licenses/zlib-copyright"
cp /usr/share/doc/gcc-13-base/copyright "$bundle/licenses/GCC-copyright"
cp /usr/share/common-licenses/GPL-3 "$bundle/licenses/GPL-3"
python3 - <<'PY'
import hashlib,json,os,pathlib,subprocess
root=pathlib.Path('/work/bundles/linux-x86_64')
files={p.relative_to(root).as_posix():{'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'size':p.stat().st_size}
       for p in sorted(root.rglob('*')) if p.is_file() and p.name != 'manifest.json'}
manifest={'schemaVersion':1,'platform':'linux-x86_64','tdlibRevision':os.environ['REVISION'],
          'tdlibSourceSha256':os.environ['SOURCE_SHA256'],'builderImage':os.environ['BUILDER_IMAGE'],
          'javaVersion':subprocess.check_output(['java','--version'],text=True).splitlines()[0], 'files':files}
(root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(root.parent.parent/'linux-builder-packages.txt').write_text(subprocess.check_output(['dpkg-query','-W'],text=True))
PY
printf '\nNative library dependencies (must contain no missing libraries):\n'
ldd "$bundle/libtdjni.so"
! ldd "$bundle/libtdjni.so" | grep -q 'not found'
python3 /work/collect-provenance.py
printf '\nNative bundle ready: %s\n' "$bundle"
