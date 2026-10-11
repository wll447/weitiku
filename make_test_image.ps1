Add-Type -AssemblyName System.Drawing

$w = 1000; $h = 420
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::White)
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

$fontBig = New-Object System.Drawing.Font("Microsoft YaHei", 40, [System.Drawing.FontStyle]::Regular)
$fontMid = New-Object System.Drawing.Font("Microsoft YaHei", 34, [System.Drawing.FontStyle]::Regular)
$brush = [System.Drawing.Brushes]::Black

$g.DrawString("中国的首都是哪里", $fontBig, $brush, 40, 40)
$g.DrawString("A. 北京    B. 上海", $fontMid, $brush, 40, 150)
$g.DrawString("C. 广州    D. 深圳", $fontMid, $brush, 40, 250)

$g.Dispose()
$out = "$PSScriptRoot\app\src\main\assets\ocr\test.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"生成: $out  ({0:N0} bytes)" -f (Get-Item $out).Length
