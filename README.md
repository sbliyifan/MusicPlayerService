# MusicPlayerService · 基于 Service 的音乐播放应用

Android 移动应用开发 · 实验七

| 项目 | 内容 |
| --- | --- |
| 姓名 / 学号 | 李易凡 / 2024110517 |
| 包名 | `cn.edu.sicnu.musicplayer` |
| APP 标题 | 李易凡2024110517（`strings.xml` 的 `app_name`） |
| 最低版本 | Android 7.0（API 24），targetSdk 34 |

## 技术要点与题目要求对应

| 题目要求 | 实现位置 |
| --- | --- |
| (1) ContentResolver 获取音乐列表 | `data/MusicScanner.kt`，查询 `MediaStore.Audio.Media` |
| (2a) 播放相关功能都在服务中 | `player/MusicService.kt`，播放/暂停/停止/上一首/下一首/跳转全部在 Service |
| (2b) Activity 通过 Binder 拿歌曲名与进度 | `MainActivity` 绑定服务，`MusicBinder.getService()` 后直接调用 |
| (3) SeekBar 实时进度（多线程）+ 拖动跳转 | Service 用 `ScheduledExecutorService` 每 500ms 回调进度；拖动时 `isUserSeeking` 防止被覆盖 |
| (4) 切歌时 Notification 显示歌名 | `MusicService.showNotification()`，含上一首/下一首动作 |
| 权限：API 33+ 用 READ_MEDIA_AUDIO | `AndroidManifest.xml` + `MainActivity.requestPermissionOrLoad()` 按版本分支 |

## 目录结构

```
MusicPlayerService/
├── app/src/main/
│   ├── java/cn/edu/sicnu/musicplayer/
│   │   ├── MainActivity.kt          控制层：绑定服务、界面刷新、权限申请
│   │   ├── SongAdapter.kt           歌曲列表适配器
│   │   ├── data/MusicScanner.kt     ContentResolver 扫描本地音乐
│   │   ├── model/Song.kt            歌曲实体
│   │   └── player/MusicService.kt   播放服务（MediaPlayer + Notification + 进度线程）
│   └── res/
│       ├── layout/{activity_main, item_song}.xml
│       ├── drawable/{bg_player, seekbar_*, bg_card, ic_launcher}.xml
│       └── values/{strings, colors, themes}.xml
└── README.md
```

## 运行

1. 用 Android Studio 打开本目录，等待 Gradle 同步；
2. **先在设备/模拟器里放几首 mp3**（模拟器可直接把文件拖进窗口，会落到 Download 目录）；
3. 运行 App，首次启动会申请「读取音乐」权限，允许即可看到列表；
4. 点任意一首开始播放，可用播放/暂停、上一首、下一首、停止按钮，拖动进度条可跳转。

未包含 `gradle-wrapper.jar`（二进制文件），若提示 Gradle 版本缺失，
在 `File > Project Structure > Project` 中选择本机已有版本即可。

## 关键实现说明

### 1. 为什么播放逻辑放在 Service

Activity 旋转屏幕或退到后台时会被销毁，如果 MediaPlayer 放在 Activity 里，音乐就断了。
放进 Service 后，界面重建只需重新 `bindService`，播放状态完全不受影响。
本项目在 `onServiceConnected` 里主动同步一次"当前歌曲 + 播放状态 + 进度"，
所以旋转屏幕后界面能立刻恢复到正确状态。

### 2. 进度更新为什么用 ScheduledExecutorService

题目要求"多线程"，进度读取不能阻塞 UI 线程。这里在 Service 中长期持有一个
单线程调度器，每 500ms 读一次 `MediaPlayer.currentPosition()`，再通过
`OnPlayerListener` 回调给 Activity（Activity 内部用 `runOnUiThread` 切主线程）。
这样做的另一个好处是：进度属于"播放状态"，由 Service 统一维护，界面销毁重建后不会丢。

### 3. 拖动进度条时为什么要加 isUserSeeking

Service 每 500ms 会回调一次进度，如果用户正在拖动，回调会把滑块"拽"回播放位置，
操作会非常难受。因此在 `onStartTrackingTouch` 里把 `isUserSeeking` 置 true，
期间忽略回调；`onStopTrackingTouch` 时才真正 `seekTo` 并恢复跟随。

### 4. 通知栏

用 `NotificationCompat.Builder` 构建，标题是歌曲名，内容是"歌手 · 播放中/已暂停"，
并带上一首、下一首两个动作（`PendingIntent.getService` 触发，在 `onStartCommand` 里处理）。
用 `startForeground` 发布，保证退到后台后系统不会回收播放服务。
Android 14 要求 Service 声明 `foregroundServiceType="mediaPlayback"`，已在 manifest 中配置。

### 5. 权限的两个版本分支

- Android 13（API 33）及以上：读取音乐用 `READ_MEDIA_AUDIO`；
- Android 12 及以下：用 `READ_EXTERNAL_STORAGE`；
- Android 13 及以上还需要 `POST_NOTIFICATIONS` 才能显示通知栏。

代码里按 `Build.VERSION.SDK_INT` 判断，只申请当前版本需要的那一个。

## 已知限制

- 只做了歌曲列表与播放控制，没有做专辑封面（用系统图标代替）；
- 没有实现播放模式（单曲循环 / 随机），可在 `playNext()` 里扩展；
- 未处理耳机拔出暂停（可通过 `AudioManager.ACTION_AUDIO_BECOMING_NOISY` 广播实现）。