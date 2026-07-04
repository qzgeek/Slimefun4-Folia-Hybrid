## 简介

基于 [Craft233MC/Slimefun4](https://github.com/Craft233MC/Slimefun4) 的 Folia 调度实现，融合了本项目的线程池优化，让粘液科技能在 Folia 1.21.11 上稳定运行。

## 具体改动

### 1. Folia 调度修正

Craft233MC 对 TickerTask 的同步 ticker 使用了正确的 `runSyncAtLocation(location)` 区域调度，确保每个机器的 tick 都在 Folia 对应的区域线程上执行，不会出现跨区域冲突。

### 2. 线程池并发优化

保留了本项目的 `SlimefunPoolExecutor` 线程池，非同步的通用 ticker 可以并发执行，提升多机器场景下的性能。

### 3. 死锁修复

`TaskUtil.runSyncMethod()` 在 Folia 区域线程上调用时，不再通过调度器派发给自己（会导致死锁），而是直接同步执行。同时 `InventoryUtil` 开箱子菜单也修正为在玩家所在区域线程操作。

### 4. 启动提示

在 Folia 环境下启动时，Banner 会显示黄色提示，提醒跨区域货网/能网仍在实验阶段。

## 效果

- 粘液科技可以在 Folia 1.21.11 上正常加载和运行
- 单区域内：指南书、合成台、机器 tick 等基础功能正常
- 适配了 Paper 和 Folia 双环境，不影响原 Paper 用户

## 已知限制

跨区域的货网和能网暂未充分测试，建议先在小范围网络中使用。

## 文件

改了 8 个文件，新增 2 个（TaskNode / TaskQueue），净增约 120 行。
