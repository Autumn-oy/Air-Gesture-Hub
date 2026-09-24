# probe/ —— 跨语言护栏的 Python 参考实现

这里的脚本是**判定逻辑的参考实现与测试向量生成器**，用来保证
「Python 参考实现 ↔ Kotlin 实现」行为一致（细节见仓库根 README 的「跨语言护栏」一节）。

| 文件 | 用途 |
|---|---|
| `detector_ref.py` | 扫动判定的 Python 参考实现（与 `SweepDetector.kt` 一一对应） |
| `test_detector_ref.py` | **29 个单元测试**，钉住判定的边界情况（慢漂移、斜扫、并拢度、冷静期…） |
| `make_sweep_vectors.py` | 由参考实现导出 **31 个跨语言测试向量**（生成 `SweepVectors.kt` 的内容） |

在仓库根目录运行：

```bash
python -m unittest probe.test_detector_ref      # 29 个测试
python probe/make_sweep_vectors.py              # 重新生成向量（会打印 Kotlin 片段）
```

只需要 Python 3.10+ 与标准库，**不需要装任何第三方包、不需要模型文件**。
（目录里没有 `__init__.py`：靠 Python 3 的隐式命名空间包工作。）

## 没有收录进来的

`make_finger_vectors.py`（手指门控的向量生成器）依赖**另一个项目**
（Windows 版隔空手势鼠标）里的 `gesture_mouse/gestures.py`，不属于本仓库范围，
因此这里没有收录 —— 相应地，Kotlin 侧 `HandFeaturesTest` 用的那批手指向量
没有随仓库提供生成器（向量本身已固化在测试代码里，测试照常可跑）。
