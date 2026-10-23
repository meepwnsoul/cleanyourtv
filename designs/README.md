# 桌面设计稿

这个分支在原项目之外设计的两套电视桌面的设计稿和网页交互样机。两套都已经做进了 `launcher/`，
对应主题是 [`themes/turntable`](../themes/turntable) 和 [`themes/bubbles`](../themes/bubbles)，用 `install.sh` 一条命令就能装到电视上。
网页样机是单个 HTML 文件，用浏览器打开后，用方向键和回车操作。

| 桌面 | 适合 | 内容 |
|---|---|---|
| [唱片机 Turntable Home](turntable-home/HANDOFF.md) | 所有电视，包括 1GB 内存的 | 转盘上的唱片标签显示焦点应用并旋转，唱片箱里每个应用是一张封套，功放上有数码管时钟和信号源、网络、设置三个旋钮。实现规格、坐标、动画参数和全部分层素材都在文件夹里 |
| [气泡 Bubble Home](bubble-home/README.md) | 高配电视（视频壁纸） | 循环视频壁纸上，应用是右侧漂浮的气泡，焦点气泡放大，邻近的气泡会让开，按确定键时气泡破开再鼓起 |

设计图和素材由 Codex（OpenAI 图像生成）生成后分层处理。按 OpenAI 的使用条款，生成内容归使用者所有。
