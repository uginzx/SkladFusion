# Агент печати этикеток SkladFusion.
# Раз в 3 секунды спрашивает у сервера задания и печатает их на принтер этикеток через драйвер Windows.
# Этикетка приходит готовой картинкой (штрихкод и кириллица уже нарисованы).
# Настройки: %LOCALAPPDATA%\SkladPrint\config.json (server, key, printer, file — для проверки без принтера).
# Журнал: %LOCALAPPDATA%\SkladPrint\agent.log
param([switch]$Once, [string]$Dir = (Join-Path $env:LOCALAPPDATA 'SkladPrint'))

$ErrorActionPreference = 'Stop'
$dir = $Dir
$cfg = Get-Content (Join-Path $dir 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$logFile = Join-Path $dir 'agent.log'
Add-Type -AssemblyName System.Drawing
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Log([string]$m) {
    try {
        if ((Test-Path $logFile) -and (Get-Item $logFile).Length -gt 1MB) { Move-Item $logFile "$logFile.old" -Force }
        "$(Get-Date -Format s) $m" | Out-File $logFile -Append -Encoding utf8
    } catch {}
}

$headers = @{ 'X-Print-Key' = $cfg.key; 'X-Print-Printer' = [uri]::EscapeDataString([string]$cfg.printer) }

function Print-Label([byte[]]$png, [double]$wmm, [double]$hmm, [int]$copies) {
    $ms = New-Object IO.MemoryStream(, $png)
    $script:img = [Drawing.Image]::FromStream($ms)
    $script:left = [Math]::Max(1, $copies)
    try {
        $doc = New-Object Drawing.Printing.PrintDocument
        $doc.DocumentName = 'Этикетка склада'
        $doc.PrinterSettings.PrinterName = [string]$cfg.printer
        if (-not $doc.PrinterSettings.IsValid) { throw "Принтер «$($cfg.printer)» не найден в Windows" }
        if ($cfg.file) { $doc.PrinterSettings.PrintToFile = $true; $doc.PrinterSettings.PrintFileName = [string]$cfg.file }
        # Размер страницы = размер этикетки (в сотых долях дюйма), без полей.
        $w = [int][Math]::Round($wmm / 25.4 * 100)
        $h = [int][Math]::Round($hmm / 25.4 * 100)
        $doc.DefaultPageSettings.PaperSize = New-Object Drawing.Printing.PaperSize('Label', $w, $h)
        $doc.DefaultPageSettings.Margins = New-Object Drawing.Printing.Margins(0, 0, 0, 0)
        $doc.add_PrintPage({
            param($sender, $e)
            $g = $e.Graphics
            $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::Half
            $ps = $e.PageSettings
            # Начало координат — край печатаемой области; сдвигаем на аппаратные поля, чтобы этикетка легла от края.
            $g.DrawImage($script:img, -$ps.HardMarginX, -$ps.HardMarginY, $ps.PaperSize.Width, $ps.PaperSize.Height)
            $script:left--
            $e.HasMorePages = $script:left -gt 0
        })
        $doc.Print()
    } finally {
        $script:img.Dispose()
        $ms.Dispose()
    }
}

Log "запуск: принтер «$($cfg.printer)», сервер $($cfg.server)"
while ($true) {
    $pause = 3
    try {
        $job = Invoke-RestMethod -Uri "$($cfg.server)/api/print/next" -Headers $headers -TimeoutSec 30
        if ($job.id) {
            $ok = $true; $err = $null
            try {
                Print-Label ([Convert]::FromBase64String($job.png)) $job.w $job.h $job.copies
                Log "напечатано задание $($job.id), копий $($job.copies)"
            } catch {
                $ok = $false; $err = $_.Exception.Message
                Log "ошибка печати задания $($job.id): $err"
            }
            $body = [Text.Encoding]::UTF8.GetBytes((@{ id = $job.id; ok = $ok; error = $err } | ConvertTo-Json -Compress))
            # Отчёт повторяем: если он не дойдёт, сервер через 2 минуты выдаст задание снова и этикетка напечатается дважды.
            for ($try = 1; $try -le 6; $try++) {
                try {
                    Invoke-RestMethod -Method Post -Uri "$($cfg.server)/api/print/done" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 30 | Out-Null
                    break
                } catch {
                    Log "отчёт о задании $($job.id) не дошёл (попытка $try): $($_.Exception.Message)"
                    Start-Sleep -Seconds 10
                }
            }
            $pause = 0 # в очереди может быть ещё
        }
    } catch {
        Log "нет связи с сервером: $($_.Exception.Message)"
        $pause = 15
    }
    if ($Once) { break }
    if ($pause -gt 0) { Start-Sleep -Seconds $pause }
}
