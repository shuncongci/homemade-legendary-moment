# 桌面工作台

下载整个仓库。`desktop/` 和仓库根目录下的 `app/` 要保留在原来的相对位置，桌面会读 `app/` 中已经公开的四份色彩文件（不需要编译 Android 应用）。用 Python **3.12**，不要在系统 Python 里装依赖。

## Windows x64

在 PowerShell 里执行：

```powershell
cd desktop
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe run.py --browser
```

以后可以在 `desktop/` 里双击 `start.cmd`。

## macOS（Apple 芯片）

在终端里执行：

```sh
cd desktop
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
sh start.sh
```

两个平台都用 [127.0.0.1:8876](http://127.0.0.1:8876/)。端口被占用时，可在 `run.py` 后加 `--port 8877`。处理完成后回到终端按 `Ctrl+C` 退出。不要将工作台设为公网或局域网服务。

## 能做什么

- **照片：**徕卡一瞬、N 瞬、冷调、暖调；支持批量操作、原图对比和 PNG、JPEG、WebP 导出。
- **视频：**徕卡一瞬、N 瞬。请使用 SDR 素材；这里不做 HDR 或杜比视频处理。视频会逐帧处理并重新编码，不是无损复制。要尝试保留首条音轨或生成有声兼容预览，先准备 FFmpeg（放入 `PATH`，或将 `LEGEND_FFMPEG` 设为可执行文件的完整路径）；没有 FFmpeg 时输出无声副本。
- **HDR 照片：**可选安装带 `libultrahdr` 的 `imagecodecs`。只有受支持的 Ultra HDR 增益图原片才可保留 HDR；缺编解码器时不会自动冒充 HDR。未提供 HDR 视频处理。

桌面公开版不包含景深、主体保护、构图模型、独立的「传奇一瞬」私有 LUT 或 GPU 运行库。N 瞬用的是 Android 已公开的 LUT，不保证与内部便携版或手机端输出逐像素一致。

片子和设置只写入本机 `workbench-data/` 和 `config.json`。移除工作台素材也会移除关联的工作台成片和记录，导出到其他位置的文件和本机原片不受影响。需要保留的成片先导出；分享带 EXIF 的文件前请检查位置等信息。

## 检查与改代码

用上述虚拟环境里的 Python 执行 `run.py --check` 可检查依赖和四份色彩资源，不会启动服务。`python -m unittest discover -s tests -v` 使用生成的测试素材（请从 `desktop/`、用同一虚拟环境执行）。

`app/` 是页面、HTTP 接口和本地任务，`src/legend/` 放颜色及图像处理。桌面原创部分沿用[仓库许可](../LICENSE)；[资源和依赖说明](THIRD_PARTY.md)列出实际来源。
