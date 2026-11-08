#!/usr/bin/env bash
# Find a device's Wireless debugging port by scanning and connect adb to it.
# Needs: Wireless debugging ON on the device, this PC already paired once.
# Usage: scripts/connect-phone.sh [device-ip]   (default: PHONE_IP from scripts/farmsync.env)
set -uo pipefail
ENV="$(dirname "$0")/farmsync.env"
[ -f "$ENV" ] && . "$ENV"
IP="${1:-${PHONE_IP:-}}"
[ -n "$IP" ] || { echo "Usage: $0 <device-ip>  (or set PHONE_IP in scripts/farmsync.env)" >&2; exit 1; }

if adb devices | grep -q "^$IP:[0-9]*[[:space:]]*device$"; then
  echo "Already connected: $(adb devices | grep "^$IP:" | cut -f1)"; exit 0
fi
adb devices | grep "^$IP:" | cut -f1 | xargs -r -n1 adb disconnect >/dev/null 2>&1

# Optional hint for VPN/mesh links that silently drop large packets (TLS then hangs).
if [ -n "${ROUTE_SUBNET:-}" ] && ! ip route show "$ROUTE_SUBNET" | grep -q 'mtu lock'; then
  echo "WARNING: no MTU route for $ROUTE_SUBNET; TLS to the device may hang. Run:" >&2
  echo "  sudo ip route replace $ROUTE_SUBNET via $ROUTE_GATEWAY dev $ROUTE_DEV mtu lock 1360 advmss 1300" >&2
fi

ping -c1 -W3 "$IP" >/dev/null || { echo "Device $IP not reachable (Wi-Fi off or new IP?)" >&2; exit 1; }

echo "Scanning $IP for the adb port..."
ports=$(seq 30000 49999 | xargs -P 400 -I{} sh -c "nc -z -w 2 $IP {} 2>/dev/null && echo {}" | sort -n)
for p in $ports; do
  timeout 6 adb connect "$IP:$p" >/dev/null 2>&1
  sleep 1
  if adb devices | grep -q "^$IP:$p[[:space:]]*device$"; then
    echo "Connected: $IP:$p"; exit 0
  fi
  adb disconnect "$IP:$p" >/dev/null 2>&1
done
echo "No adb port found. Is Wireless debugging ON?" >&2
exit 1
