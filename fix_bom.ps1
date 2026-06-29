$paths = @(
    'src\\com\\codeinsight\\servlet\\RunServlet.java',
    'src\\com\\codeinsight\\servlet\\SubmitServlet.java',
    'src\\com\\codeinsight\\servlet\\AIInsightServlet.java'
)
foreach ($path in $paths) {
    $full = Join-Path (Get-Location) $path
    if (Test-Path $full) {
        $text = [System.IO.File]::ReadAllText($full)
        [System.IO.File]::WriteAllText($full, $text, New-Object System.Text.UTF8Encoding($false))
        Write-Host "Rewrote: $path"
    } else {
        Write-Host "Missing: $path"
    }
}
