# CeleritasCTM

[English](https://github.com/TLSseven/CeleritasCTM/blob/main/README_EN.md)

面向 **Minecraft 1.12.2 + Cleanroom Loader + Celeritas/Actinium** 的连接纹理（CTM）与自然纹理兼容模组，实现了Optifine的部分材质功能。

> **本模组由 AI 编写，不保证后续维护。**
>
> **兼容测试对象是 Conquest Reforged 1.12.2 连接纹理材质包，不保证其它材质兼容性。**

---

## 功能

### 1. OptiFine CTM 兼容（连接纹理 / 随机纹理）

从资源包加载 OptiFine 格式的 `.properties` 规则，在 Celeritas 渲染管线下逐 quad 应用：

### 2. 更好的草地（Better Grass）

草方块侧面沿对角下侧同种方块方向延伸草皮（连接草皮），关闭时恢复原版双层侧面。可在配置中开关。

### 3. 更好的雪地（Better Snow）

雪层下方的方块侧面显示雪纹理（含草方块积雪侧面）。可在配置中开关。

### 4. 无缝玻璃（Connected Glass）

原版的玻璃与染色玻璃连接时可实现无缝。可在配置中开关。

### 5. BetterFoliage兼容

使 BetterFoliage 模组在 Celeritas 渲染管线下也可实现额外树叶、短草等功能（可在配置中调整是否由BetterFoliage接管草地渲染）。

### 6. 内置配置菜单

与 Celeritas **视频设置**界面集成的内置配置菜单。


---

## 前置要求

| 依赖 | 版本 |
|---------|---------|
| Minecraft | 1.12.2 |
| Cleanroom Loader | 0.6.7+ |
| Celeritas / Actinium | 2.4.0+ |

---

## 已确认兼容的模组

- ConquestReforged 3.0.2
- BetterFoliage 2.3.3（需要Forgelin-Continuous）
- Decocraft 2.6.3.7
- Aperture 1.8.2
- Blockbuster 2.7.1
- Chameleon 1.2.2
- Emoticons 1.1.2
- Metamorph 1.4
- Minema 3.7.1

---

## 兼容性说明

- 本模组**兼容测试对象是 Conquest Reforged 1.12.2 连接纹理材质包，不保证其它材质兼容性**。
- 仅支持 vanilla / 原版模型方块的 CTM 处理；mod 自定义模型（如 Chisel & Bits）会被跳过以免纹理错乱。
- 资源包应使用 OptiFine 标准 `.properties` 规则；若某规则未生效，请检查规则文件路径与语法。

