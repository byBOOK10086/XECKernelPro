#!/system/bin/sh

MODDIR=${0%/*}

(
until [ $(getprop sys.boot_completed) -eq 1 ] ; do
  sleep 5
done

chmod 777 ${MODDIR}/bin/arm64-v8a/selinuxfix
${MODDIR}/bin/arm64-v8a/selinuxfix
)
