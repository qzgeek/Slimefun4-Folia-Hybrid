# 粘液科技 Folia 混合移植 — 跨区域货网方向

## 已完成

### 线程安全
- InventoryUtil.openInventory Folia 适配
- TaskUtil.runSyncMethod isTickThread 死锁检测
- Slimefun.runSyncAtLocation isOwnedByCurrentRegion 同区短路
- ADataController.invokeCallback 异步回调区域线程派发

### 网络发现（启动/重启）
- classifyLocation: 缓存未命中时 SQL 直查 getBlockSfId
- getFrequency: 缓存未命中时 SQL 直查回退
- onClassificationChange: NPE 防护

### 方块拆除
- BlockDataController.removeBlock: 数据未加载时 addBlockDirectly

### 基础设施
- attachedBlockCache: Location→Location 箱子位置缓存
- FoliaRegionHelper: isSameRegion / getRegionKey
- TransferRequest / TransferDispatcher / TransferTickTask 框架

## 未解决的核心矛盾

火焰遗弃（fire-and-forget）无法原子验证跨区域插入结果：

- 单线程 Folia: CompletableFuture.get() 死锁（同一线程排队）
- 多线程 Folia: CF.get() 可行，但测试环境为单线程
- 不用 get(): 无法确认插入成功 → 物品可能在途中丢失

## 尝试过的方案

1. 同步 CompletableFuture.get() — 单线程死锁
2. 火焰遗弃 + 退回链路 — 无法验证原子性
3. 管理器线程路由 + 目标线程执行 — 同 1/2
4. 缓存路由决策 — attachedBlockCache 首次为空

## 文件变更

| 文件 | 变更 |
|------|------|
| CargoNetworkTask.java | 跨区域分发逻辑（多次重构） |
| CargoNet.java | classifyLocation/onClassificationChange/getFrequency |
| BlockDataController.java | removeBlockDirectly + getBlockSfId |
| ADataController.java | invokeCallback 区域线程派发 |
| Slimefun.java | runSyncAtLocation/isFolia/getPlatformScheduler |
| TaskUtil.java | isTickThread + runSyncMethod 死锁修复 |
| InventoryUtil.java | Folia 线程安全 |
| AbstractItemNetwork.java | attachedBlockCache |
| FoliaRegionHelper.java | 新建 |
| TransferRequest.java | 新建 |
| TransferDispatcher.java | 新建 |
| TransferTickTask.java | 新建 |

## 单区域内已验证稳定

- 货网运输 ✅
- 能网 ✅
- 指南书 ✅
- 合成台 ✅
- 方块拆除重放 ✅
