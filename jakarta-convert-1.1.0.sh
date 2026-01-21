#!/bin/bash
# OpenCMIS 1.1.0 Jakarta EE 10 変換スクリプト
# 1.2.0-SNAPSHOTの変換手順をベースに1.1.0を変換

set -e

echo "=== OpenCMIS 1.1.0 Jakarta化変換開始 ==="

# 作業ディレクトリ確認
if [ ! -d "chemistry-opencmis-1.1.0-nemakiware" ]; then
    echo "ERROR: chemistry-opencmis-1.1.0-nemakiware ディレクトリが見つかりません"
    exit 1
fi

cd chemistry-opencmis-1.1.0-nemakiware

echo "Step 1: javax.servlet -> jakarta.servlet 変換"

# 1.2.0と同様のJakarta変換
find . -name "*.java" -type f -exec sed -i '' 's/import javax\.servlet\./import jakarta.servlet./g' {} \;
find . -name "*.java" -type f -exec sed -i '' 's/javax\.servlet\./jakarta.servlet./g' {} \;

echo "Step 2: javax.xml.ws -> jakarta.xml.ws 変換 (1.1.0固有)"

# 1.1.0にもWebサービス関連があればJakarta化
find . -name "*.java" -type f -exec sed -i '' 's/import javax\.xml\.ws\./import jakarta.xml.ws./g' {} \;
find . -name "*.java" -type f -exec sed -i '' 's/javax\.xml\.ws\./jakarta.xml.ws./g' {} \;

echo "Step 3: javax.annotation -> jakarta.annotation 変換"

find . -name "*.java" -type f -exec sed -i '' 's/import javax\.annotation\./import jakarta.annotation./g' {} \;
find . -name "*.java" -type f -exec sed -i '' 's/javax\.annotation\./jakarta.annotation./g' {} \;

echo "Step 4: pom.xml依存関係更新"

# 1.2.0と同様の依存関係更新パターンを適用
find . -name "pom.xml" -type f -exec sed -i '' 's/<groupId>javax\.servlet</<groupId>jakarta.servlet</g' {} \;
find . -name "pom.xml" -type f -exec sed -i '' 's/<artifactId>servlet-api</<artifactId>servlet-api</g' {} \;
find . -name "pom.xml" -type f -exec sed -i '' 's/<artifactId>javax\.servlet-api</<artifactId>jakarta.servlet-api</g' {} \;

echo "Step 5: 変換結果確認"

# 変換されたファイル数確認
echo "javax.servlet変換ファイル数:"
find . -name "*.java" -type f -exec grep -l "jakarta.servlet" {} \; | wc -l

echo "残存javax.servlet参照確認:"
find . -name "*.java" -type f -exec grep -l "javax.servlet" {} \; | head -5

echo "=== OpenCMIS 1.1.0 Jakarta化変換完了 ==="
echo "バージョン: 1.1.0-nemakiware"
echo "次のステップ: Jakarta EE 10対応JAR生成"