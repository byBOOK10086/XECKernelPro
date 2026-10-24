package me.weishu.kernelsu.magica;

import static me.weishu.kernelsu.magica.AppZygotePreload.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import me.weishu.kernelsu.ui.util.KsuCliKt;
import com.xecpro.kernel.XecMagicaService;

public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        var action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"me.weishu.kernelsu.magica.LAUNCH".equals(action)) {
            return;
        }
        if (KsuCliKt.rootAvailable()) return;
        // 路径一：Magica（app-zygote 预载 exec ksud late-load）——内核已打
        // kpimg/KPM 的设备的快路。
        try {
            context.startService(new Intent(context, XecMagicaService.class));
            Log.i(TAG, "MagicaService started from boot action: " + action);
        } catch (Throwable e) {

            Log.e(TAG, "Failed to start MagicaService from boot action: " + action, e);
        }
        // 路径二：自动重放（GhostLock 漏洞）——BL 锁定原厂内核设备。服务自己
        // 会先等 Magica 的窗口，等到了就直接收工，不会白跑漏洞。
        try {
            Intent replay = new Intent(context, me.weishu.kernelsu.privilege.AutoReplayService.class);
            context.startForegroundService(replay);
            Log.i(TAG, "AutoReplayService started from boot action: " + action);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to start AutoReplayService from boot action: " + action, e);
        }
    }
}
