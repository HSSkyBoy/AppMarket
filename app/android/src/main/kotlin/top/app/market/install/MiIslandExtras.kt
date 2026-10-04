package top.app.market.install

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import top.app.market.R
import top.app.market.data.install.HyperOsFocusCapability.FocusMode
import com.xzakota.hyper.notification.focus.FocusNotification

// 焦点通知 / 超级岛载荷；OS3 用模版21（含岛胶囊），OS2 回退模版7（多识别图形组件1）。
internal object MiIslandExtras {

    data class Action(
        val key: String,
        val title: String,
        val pendingIntent: PendingIntent,
    )

    fun build(
        context: Context,
        mode: FocusMode,
        title: String,
        content: String,
        percent: Int?,
        float: Boolean,
        appIcon: Bitmap? = null,
        capsuleStatus: String? = null,
        dismissAfterSeconds: Int? = null,
        actions: List<Action> = emptyList(),
    ): Bundle = when (mode) {
        FocusMode.OS3 -> FocusNotification.buildV3 {
            val logo = createPicture("key_logo", Icon.createWithResource(context, R.drawable.ic_launcher))
            val avatar = appIcon?.let { createPicture("key_avatar", Icon.createWithBitmap(it)) } ?: logo

            islandFirstFloat = float
            enableFloat = float
            updatable = true
            ticker = title
            tickerPic = logo

            island {
                islandProperty = 1
                dismissAfterSeconds?.let { islandTimeout = it }
                bigIslandArea {
                    // 图文组件5：应用图标 + 状态文字（type=5，picInfo type=4）
                    imageTextInfoLeft {
                        type = 5
                        picInfo {
                            type = 4
                            pic = avatar
                        }
                        textInfo {
                            this.title = (capsuleStatus ?: content).ifEmpty { title }
                        }
                    }
                    if (percent != null) {
                        progressTextInfo {
                            textInfo {
                                this.title = percent.toString()
                                this.content = "%"
                            }
                            progressInfo {
                                isCCW = true
                                progress = percent
                            }
                        }
                    } else {
                        imageTextInfoRight {
                            type = 2
                            textInfo {
                                this.title = title
                            }
                        }
                    }
                }
                smallIslandArea {
                    picInfo {
                        type = 1
                        pic = avatar
                    }
                }
            }

            chatInfo {
                this.title = title
                this.content = content.ifEmpty { " " }
                picProfile = avatar
                picProfileDark = avatar
            }
            // 进度组件与按钮组件在官方模版里互斥：有进度优先进度条，否则才挂按钮
            if (percent != null) {
                multiProgressInfo {
                    progress = percent
                }
            } else if (actions.isNotEmpty()) {
                textButton {
                    actions.take(2).forEach { item ->
                        addActionInfo {
                            val nativeAction = Notification.Action.Builder(
                                Icon.createWithResource(context, R.drawable.ic_launcher),
                                item.title,
                                item.pendingIntent,
                            ).build()
                            action = createAction(item.key, nativeAction)
                            actionTitle = item.title
                        }
                    }
                }
            }
        }

        FocusMode.OS2 -> FocusNotification.buildV2 {
            val logo = createPicture("key_logo", Icon.createWithResource(context, R.drawable.ic_launcher))
            val avatar = appIcon?.let { createPicture("key_avatar", Icon.createWithBitmap(it)) } ?: logo

            enableFloat = float
            updatable = true
            ticker = title
            tickerPic = logo

            chatInfo {
                this.title = title
                this.content = content.ifEmpty { " " }
                picProfile = avatar
                picProfileDark = avatar
            }
            if (percent != null) {
                progressInfo {
                    progress = percent
                }
            }
            picInfo {
                type = 1
                pic = logo
                picDark = logo
            }
        }
    }
}
