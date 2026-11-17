#!/data/data/com.termux/files/usr/bin/bash
# Liga o bloqueio de teclas do PonteTV na TV por ADB, sem abrir a tela de Acessibilidade.
# Rode UMA vez, no Termux do celular, com celular e TV na mesma rede:  bash ativar-bloqueio.sh IP_DA_TV
IP="${1:-}"
if [ -z "$IP" ]; then read -rp "IP da TV (aparece no passo a passo do PonteTV Hub): " IP; fi
command -v adb >/dev/null 2>&1 || { echo "Instalando o adb..."; pkg install -y android-tools; }
T="$IP:5555"
echo "Conectando em $T (aceite 'Permitir depuração' na TV)..."
adb connect "$T"
sleep 3
SVC="com.pontetv.tv/com.pontetv.tv.KeyFilterService"
adb -s "$T" shell pm grant com.pontetv.tv android.permission.WRITE_SECURE_SETTINGS
CUR="$(adb -s "$T" shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$CUR" in
  *"$SVC"*) ;;
  ""|null) CUR="$SVC" ;;
  *) CUR="$CUR:$SVC" ;;
esac
adb -s "$T" shell settings put secure enabled_accessibility_services "$CUR"
adb -s "$T" shell settings put secure accessibility_enabled 1
echo "Pronto. Abra o PonteTV Hub na TV: 'Bloqueio de teclas na TV' deve aparecer como ligado."
