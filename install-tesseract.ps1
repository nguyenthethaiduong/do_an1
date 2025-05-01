# Tạo thư mục tessdata nếu chưa tồn tại
New-Item -ItemType Directory -Force -Path "tessdata"

# Tải Tesseract installer
$installerUrl = "https://github.com/UB-Mannheim/tesseract/wiki/tesseract-ocr-w64-setup-5.3.3.20231005.exe"
$installerPath = "tesseract-installer.exe"
Invoke-WebRequest -Uri $installerUrl -OutFile $installerPath

# Cài đặt Tesseract
Start-Process -FilePath $installerPath -ArgumentList "/S" -Wait

# Tải dữ liệu training tiếng Việt
$tessdataUrl = "https://github.com/tesseract-ocr/tessdata/raw/main/vie.traineddata"
$tessdataPath = "tessdata\vie.traineddata"
Invoke-WebRequest -Uri $tessdataUrl -OutFile $tessdataPath

# Xóa installer
Remove-Item $installerPath

Write-Host "Tesseract OCR đã được cài đặt thành công!" 