# QHIFI 音频待办（原第二轮修复计划归档）

> 原《QHIFI 第二轮修复方案（v1.8.5-hifi 候选）》的正文已删除：其中的音量曲线、焦点策略、拔线暂停、阶段 1 预解析均已落地，继续保留只会与代码漂移。需要考古时看 git 历史。
>
> 本文件只保留未完成或未验证的事项，不描述当前实现。实现状态一律以代码为准。

## 待办

1. **预解码阶段 2 未实现**：当前自然切歌依赖 sink 复用与环形缓冲。原“必须预解码才能解决首播卡顿”的论证已被实测否定；只有真机仍然出现可闻缺口时，再评估第二解码器预热。
2. **在线源“整首噪声”未定位**：部分在线曲整首无法聆听、其余正常，部分曲目会弹「已为该歌曲自动换源」。已排除与音量改动、与播放中换源有关。两条待验假设：
   - PCM 类型错配：`awaitOutputFormat` 可能超时返回 null → 类型默认 16bit，而在线源请求 float（`probeBitDepth(http)` 恒 0）；解码器随后在 `INFO_OUTPUT_FORMAT_CHANGED` 报出 float 时 `drainOutput` 只更新字段、不重建 sink。
   - 插件换源流本身的字节有问题：`unblock` 产物 `mimeType:""`、`trial:false`、`cacheable:true`，被当成完整文件缓存。
   排查前先取真机日志，不要直接改 `openSink`；曾按第一条做过一次 `openSink` 重建修正，已按用户要求回退。
3. **sink 复用判据不含位深**：只比采样率、声道数、floatPCM；位深只影响 native 选哪个 alt-setting。与噪声排查相关，暂未改动。
4. **关闭独占后的清理不彻底**：`setHifiEnabled(false)` 不立即切回 system，也不 close 独占 sink；DAC 可能仍被 usbfs 占用。需真机确认框架路径能否用到该 DAC。
5. **桌面端 About 链接待确认**：`desktop-host` 的 `openRepo` 仍指向上游 `TIMER-err/qplayer`；若全局仓库约束也覆盖桌面，需要改 `Main.java`。
6. **独占开流的电平在 fade-in 前可能停在旧值**：`openSink` 用的是直出后端上次收到的 `volumePercent`，而独占电平是在开流后的 fade-in 里才发下去的，所以开流瞬间 DAC 可能短暂停在旧档位。可接受但可优化：让 dispatcher 在切换后端时先补发当前电平。
