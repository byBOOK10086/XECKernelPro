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
        // USER_UNLOCKED 取代 BOOT_COMPLETED：不再声明 RECEIVE_BOOT_COMPLETED，
        // 且此时 zygote / CE 存储都已就绪，自动重放拿 root 的成功率更高。
        if (!Intent.ACTION_USER_UNLOCKED.equals(action)
                && !"me.weishu.kernelsu.magica.LAUNCH".equals(action)) {
            return;
        }
        // receiver 常开（隐身入口也靠它补拉守卫），自动越狱路径仍按开关自门控。
        boolean autoJailbreak = context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("auto_jailbreak", false);
        if (!autoJailbreak) return;
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
