mvn clean package -DskipTests > build.log 2>&1
if ($LASTEXITCODE -eq 0) {
    'success' | Out-File build.status -Encoding utf8
} else {
    'failed' | Out-File build.status -Encoding utf8
}
