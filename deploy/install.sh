#!/usr/bin/env bash
# AgentPulse Installer
# Installs AgentPulse to ~/.local/share/agentpulse and symlinks pulse to ~/.local/bin/pulse
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

DEFAULT_INSTALL_DIR="${HOME}/.local/share/agentpulse"
INSTALL_DIR="${AGENTPULSE_INSTALL_DIR:-${DEFAULT_INSTALL_DIR}}"
BIN_DIR="${HOME}/.local/bin"

ALLOW_IP=""

while [ $# -gt 0 ]; do
    case "$1" in
        --dir)
            INSTALL_DIR="$2"
            shift 2
            ;;
        --dir=*)
            INSTALL_DIR="${1#*=}"
            shift
            ;;
        --allow)
            ALLOW_IP="$2"
            shift 2
            ;;
        --allow=*)
            ALLOW_IP="${1#*=}"
            shift
            ;;
        -h|--help)
            cat <<EOF
用法: $(basename "$0") [选项]

选项:
  --dir <path>       指定安装根目录 (默认: ~/.local/share/agentpulse)
  --allow <ip>       预置放行的手机 IP (写入 config/allowed-ip)
  -h, --help         显示帮助信息
EOF
            exit 0
            ;;
        *)
            echo "错误: 未知参数 $1" >&2
            exit 1
            ;;
    esac
done

echo "=== 开始安装 AgentPulse ==="
echo "安装目录: ${INSTALL_DIR}"
echo "命令路径: ${BIN_DIR}/pulse"

# 1. 创建必要目录
mkdir -p "${INSTALL_DIR}" "${BIN_DIR}" "${INSTALL_DIR}/run" "${INSTALL_DIR}/logs" "${INSTALL_DIR}/config" "${INSTALL_DIR}/download"

# 2. 复制 service/ 代码
echo "正在复制服务代码..."
rm -rf "${INSTALL_DIR}/service"
cp -r "${REPO_DIR}/service" "${INSTALL_DIR}/service"

# 3. 复制 pulse 并设置可执行权限
echo "正在安装 pulse 脚本..."
cp "${SCRIPT_DIR}/pulse" "${INSTALL_DIR}/pulse"
chmod +x "${INSTALL_DIR}/pulse"

# 4. 创建软链接
echo "正在建立命令软链接..."
ln -sf "${INSTALL_DIR}/pulse" "${BIN_DIR}/pulse"

# 5. 配置 Python 虚拟环境与依赖
VENV_DIR="${INSTALL_DIR}/.venv"
if [ ! -d "${VENV_DIR}" ]; then
    echo "正在创建虚拟环境并安装依赖 (${VENV_DIR})..."
    python3 -m venv "${VENV_DIR}"
    "${VENV_DIR}/bin/pip" install --quiet -r "${INSTALL_DIR}/service/requirements.txt"
elif [ ! -f "${VENV_DIR}/bin/uvicorn" ]; then
    echo "正在补全依赖包..."
    "${VENV_DIR}/bin/pip" install --quiet -r "${INSTALL_DIR}/service/requirements.txt"
else
    echo "Python 虚拟环境已就绪。"
fi

# 6. 配置放行 IP
ALLOWED_IP_FILE="${INSTALL_DIR}/config/allowed-ip"
if [ -n "${ALLOW_IP}" ]; then
    echo "${ALLOW_IP}" > "${ALLOWED_IP_FILE}"
    echo "放行 IP 已设置为: ${ALLOW_IP}"
elif [ ! -f "${ALLOWED_IP_FILE}" ]; then
    echo "10.103.111.190" > "${ALLOWED_IP_FILE}"
    echo "已初始化默认放行 IP: 10.103.111.190"
fi

# 7. 复制已有 APK 文件（如果存在）
if [ -d "${HOME}/agentpulse/firstmate/data/agentpulse-download" ]; then
    cp -n "${HOME}/agentpulse/firstmate/data/agentpulse-download/"*.apk "${INSTALL_DIR}/download/" 2>/dev/null || true
fi

echo
echo "=== AgentPulse 安装完成！ ==="
echo "命令位置: $(command -v pulse 2>/dev/null || echo "${BIN_DIR}/pulse")"
echo
echo "常用命令:"
echo "  pulse start       启动监控服务与下载服务"
echo "  pulse status      查看服务运行状态与手机连接"
echo "  pulse logs -f     实时查看服务日志"
echo "  pulse allow <ip>  修改放行手机 IP"
echo "  pulse stop        停止服务并清理防火墙"
echo "  pulse help        查看完整帮助"
echo
