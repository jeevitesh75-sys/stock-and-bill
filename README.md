# Stock & Bill (offline barcode billing app)

Scan to add stock, scan to sell, low-stock alert at 5 or less. No internet, no database. Data is saved as files in a folder you choose on the phone.

## Get the APK (from Termux)
1. unzip stock-and-bill.zip && cd stock-and-bill
2. git init && git add -A && git commit -m "Stock and Bill"
3. Create an empty repo on GitHub (e.g. stock-and-bill), then:
   git branch -M main
   git remote add origin https://github.com/<you>/stock-and-bill.git
   git push -u origin main
4. On GitHub open the Actions tab and wait for "Build APK" (about 4 to 6 minutes).
5. Open Releases > "Stock & Bill APK" > download StockAndBill.apk, install (allow "install unknown apps").
6. First launch: choose a folder such as Documents/StockData.
