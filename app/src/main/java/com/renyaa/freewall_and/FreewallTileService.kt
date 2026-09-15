package com.renyaa.freewall_and

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * 상단바 알림창을 아래로 내렸을 때 표시되는 빠른 설정 타일 (Quick Settings Tile)
 * 마치 일반 VPN처럼 원터치로 Freewall 우회를 켜고 끌 수 있음
 */
@RequiresApi(Build.VERSION_CODES.N)
class FreewallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val isRunning = FreewallVpnService.isRunning

        if (isRunning) {
            // 실행 중이면 중지
            val stopIntent = Intent(this, FreewallVpnService::class.java).apply {
                action = FreewallVpnService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // VPN 권한 필요 여부 확인
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                // 최초 1회 시스템 VPN 권한 승인이 필요한 경우 메인 화면 오픈
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (isLocked) {
                    unlockAndRun {
                        startActivityAndCollapse(appIntent)
                    }
                } else {
                    startActivityAndCollapse(appIntent)
                }
                return
            }

            // 즉시 VPN 시작
            val startIntent = Intent(this, FreewallVpnService::class.java).apply {
                action = FreewallVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }

        // 잠시 후 타일 상태 반영
        qsTile?.let {
            it.state = if (!isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.subtitle = if (!isRunning) "보호 켜짐" else "꺼짐"
            it.updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRunning = FreewallVpnService.isRunning

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Freewall"
        tile.subtitle = if (isRunning) "보호 켜짐" else "꺼짐"
        tile.updateTile()
    }
}
