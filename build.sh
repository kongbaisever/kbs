#!/system/bin/sh
# ==============================================================================
#  珍珠炮码计算 —— MT 管理器 / Termux 构建脚本
#
#  用法：
#      sh build.sh            # 构建 debug APK
#      sh build.sh release    # 构建 release APK（需先配置签名环境变量）
# ==============================================================================

set -e

echo "=============================================="
echo " 珍珠炮码计算 构建脚本"
echo "=============================================="
echo ""

# ------------------------------------------------------------------------------
# 定位工程目录（以脚本所在位置为准，避免依赖当前工作目录）
# ------------------------------------------------------------------------------
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
echo "[1/5] 工程目录: $DIR"

# ------------------------------------------------------------------------------
# 定位 gradle 可执行文件
#
# 优先用项目自带的 wrapper；若 wrapper jar 缺失（从 GitHub 源码包解压时常缺），
# 则回退到环境中已安装的 gradle。
# ------------------------------------------------------------------------------
GRADLE=""
if [ -x "$DIR/gradlew" ] && [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    GRADLE="$DIR/gradlew"
    echo "[2/5] 使用项目 wrapper: gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE="gradle"
    echo "[2/5] 使用系统 gradle: $(command -v gradle)"
    echo "      版本: $(gradle --version 2>/dev/null | grep Gradle | head -1)"
else
    echo ""
    echo "[错误] 找不到 gradle，且项目缺少 wrapper。"
    echo ""
    echo "请选择一种方式："
    echo "  ① 补齐 wrapper：下载 gradle-wrapper.jar 放到 gradle/wrapper/ 下"
    echo "  ② 在 MT 管理器的终端里安装 gradle 后重试"
    echo ""
    exit 1
fi

# ------------------------------------------------------------------------------
# 定位 JAVA_HOME
#
# Android Gradle Plugin 8.x 需要 JDK 17。
# 常见位置的优先级：环境变量 → Termux → MT 内置 → 标准路径
# ------------------------------------------------------------------------------
echo "[3/5] 检查 Java 环境"

find_java() {
    # 已设置且可用
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME"
        return 0
    fi
    # 按常见路径逐个探测
    for d in \
        /data/data/com.termux/files/usr/lib/jvm/java-17-openjdk \
        /data/data/com.termux/files/usr/lib/jvm/java-17 \
        /data/data/com.termux/files/usr/opt/openjdk \
        /data/data/bin.mt.plus/jre \
        /data/data/bin.mt.plus/jre17 \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk \
        /opt/java/openjdk
    do
        if [ -x "$d/bin/javac" ]; then
            echo "$d"
            return 0
        fi
    done
    # PATH 里有 javac 就往上推两级
    if command -v javac >/dev/null 2>&1; then
        p="$(command -v javac)"
        echo "$(dirname "$(dirname "$p")")"
        return 0
    fi
    return 1
}

if JAVA_DIR="$(find_java)"; then
    export JAVA_HOME="$JAVA_DIR"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "      JAVA_HOME = $JAVA_HOME"
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/^/      /'
else
    echo ""
    echo "[错误] 找不到 JDK 17。"
    echo "       Android Gradle Plugin 8.x 需要 JDK 17，请先在 MT 中安装。"
    echo ""
    exit 1
fi

# ------------------------------------------------------------------------------
# 校验 JDK 主版本
# ------------------------------------------------------------------------------
JAVA_MAJOR="$("$JAVA_HOME/bin/java" -version 2>&1 |
    head -1 |
    sed -E 's/.*version "([0-9]+).*/\1/')"
case "$JAVA_MAJOR" in
    17|18|19|20|21) ;;
    *)
        echo "[警告] 当前 JDK 版本为 $JAVA_MAJOR，AGP 8.x 推荐 JDK 17。"
        echo "       若构建失败，请先切换到 JDK 17。"
        ;;
esac

# ------------------------------------------------------------------------------
# 选择构建变体
# ------------------------------------------------------------------------------
VARIANT="Debug"
if [ "$1" = "release" ]; then
    VARIANT="Release"
    # release 需要签名；检查环境变量是否齐全
    MISSING=""
    for v in RELEASE_STORE_FILE RELEASE_STORE_PASSWORD \
             RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
        eval "val=\$$v"
        if [ -z "$val" ]; then
            MISSING="$MISSING $v"
        fi
    done
    if [ -n "$MISSING" ]; then
        echo "[提示] 缺少签名环境变量:$MISSING"
        echo "       将构建未签名的 release APK（无法安装）。"
        echo "       如需签名，请先导出这些变量再运行。"
        echo ""
    fi
fi

echo ""
echo "[4/5] 开始构建 $VARIANT APK"
echo "      首次构建需下载依赖，可能耗时较久，请耐心等待。"
echo ""

# --no-daemon：MT 环境内存有限，常驻 daemon 容易 OOM
$GRADLE --no-daemon "assemble$VARIANT"

# ------------------------------------------------------------------------------
# 定位并检查产物
# ------------------------------------------------------------------------------
OUT_DIR="$DIR/app/build/outputs/apk/$(echo "$VARIANT" | tr 'A-Z' 'a-z')"
APK="$(ls -1t "$OUT_DIR"/*.apk 2>/dev/null | head -1)"

echo ""
echo "[5/5] 检查产物"

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "[错误] 未找到 APK，构建可能失败。"
    exit 1
fi

SIZE="$(du -h "$APK" 2>/dev/null | cut -f1)"
echo "      APK: $APK"
echo "      大小: $SIZE"

# APK 本质是 ZIP，用 unzip -t 验证完整性。
# 常见错误 "invalid distance code" 就是传输/打包损坏导致的，
# 在这里提前发现，避免装到手机上才发现。
if command -v unzip >/dev/null 2>&1; then
    if unzip -t "$APK" >/dev/null 2>&1; then
        echo "      完整性: ✓ 通过 (unzip -t)"
    else
        echo "      完整性: ✗ 损坏 —— 请重新构建"
        exit 1
    fi
fi

if command -v sha256sum >/dev/null 2>&1; then
    echo "      SHA256: $(sha256sum "$APK" | cut -c1-32)…"
fi

echo ""
echo "=============================================="
echo " 构建完成 ✓"
echo "=============================================="
echo ""
echo "下一步："
echo "  1. 在手机文件管理器中打开 $APK"
echo "  2. 若提示「卸载旧版本」，请先卸载 —— "
echo "     applicationId 变更过（kbs → kbs.pearl），"
echo "     系统会视为不同应用。"
echo ""
