# NemakiWare Test Environment Setup

This directory contains scripts for setting up a test environment with sample data in NemakiWare.

## Overview

The setup script populates a clean NemakiWare instance with:
- Organizations (groups) with hierarchical structure
- Users belonging to each organization
- Folder structures with appropriate permissions
- Custom content types (test:invoice, test:contract)
- Sample documents (Word, PowerPoint, PDF, Excel)

## Requirements

- Python 3.8+
- A running NemakiWare instance
- Network access to the NemakiWare server

## Technical Notes

### CMIS API

This script uses the **CMIS Browser Binding API** directly via HTTP requests. This approach was chosen because:

1. Browser Binding is the recommended binding for modern CMIS clients
2. Direct HTTP calls provide better control over request parameters
3. No external CMIS client library dependencies required

**Note**: NemakiWare 3.0.0+ is compatible with the Python cmislib library. The service document endpoint (`/atom`) now supports requests without a repository ID, and INTEGER property types properly handle BigInteger values per the CMIS 1.1 specification.

## Installation

```bash
cd test-data-setup
pip install -r requirements.txt
```

## Configuration

Copy `config.yaml.example` to `config.yaml` and edit to customize:

```bash
cp config.yaml.example config.yaml
# Edit config.yaml with your settings
```

### Environment Variables (Optional)

You can override config file values using environment variables for security:

| Variable | Description |
|----------|-------------|
| `NEMAKI_CMIS_BROWSER_URL` | CMIS Browser Binding URL |
| `NEMAKI_CMIS_USERNAME` | CMIS username |
| `NEMAKI_CMIS_PASSWORD` | CMIS password |
| `NEMAKI_REST_URL` | REST API base URL |
| `NEMAKI_REST_USERNAME` | REST API username |
| `NEMAKI_REST_PASSWORD` | REST API password |
| `NEMAKI_REPOSITORY_ID` | Repository ID |

Example:
```bash
export NEMAKI_CMIS_USERNAME=admin
export NEMAKI_CMIS_PASSWORD=your_password
python setup_test_environment.py
```

### Configuration Options

- **CMIS connection settings**: Browser Binding URL, repository ID, credentials
- **REST API settings**: Base URL, credentials
- **Organization structure**: Add/remove/modify organizations
- **User settings**: Number of users per organization, default password
- **Folder structure**: Organization folders under /sites
- **Root folders**: Internal regulations, contracts, invoices
- **Custom types**: Invoice and contract type definitions
- **Content templates**: Document titles, company names, invoice items

## Usage

```bash
# Using default config.yaml
python setup_test_environment.py

# Using custom configuration file
python setup_test_environment.py --config /path/to/custom_config.yaml
```

## Created Data Structure

### Organizations (Groups)
- 取締役 (Board of Directors)
- 総務部 (General Affairs Department)
- 法務部 (Legal Department)
- 営業部 (Sales Department)
  - 第一営業課 (Sales Division 1)
  - 第二営業課 (Sales Division 2)
- 情報システム部 (Information Systems Department)

### Users
Each organization has 3 users named `{org_id}1`, `{org_id}2`, `{org_id}3`.

### Folder Structure

```
/
├── sites/
│   ├── 取締役/
│   ├── 総務部/
│   ├── 法務部/
│   ├── 営業部/
│   │   ├── 第一営業課/
│   │   └── 第二営業課/
│   └── 情報システム部/
├── 社内規程/          (10 Word files, test:policy type)
├── 契約書/            (40 PDF files, test:contract type)
└── インボイス/        (100 Excel files, test:invoice type)
```

### Permissions

| Folder | READ | WRITE |
|--------|------|-------|
| Organization folders | Members only | Members only |
| 社内規程 | Everyone | 総務部 |
| 契約書 | Everyone | Everyone |
| インボイス | Everyone | System/Admin only |

### Custom Content Types

**test:invoice** (extends nemaki:document) - 請求書
- `test:invoiceNumber` (String): 請求番号
- `test:customerName` (String): 顧客名
- `test:amount` (Integer): 請求金額
- `test:issueDate` (DateTime): 発行日
- `test:invoiceStatus` (String): ステータス (draft/sent/paid/cancelled)

**test:contract** (extends nemaki:document) - 契約書
- `test:contractNumber` (String): 契約番号
- `test:partyName` (String): 契約相手方
- `test:startDate` (DateTime): 契約開始日
- `test:endDate` (DateTime): 契約終了日
- `test:contractType` (String): 契約種別 (service/sales/nda/lease/other)

**test:policy** (extends nemaki:document) - 社内規程
- `test:policyNumber` (String): 規程番号
- `test:department` (String): 管理部門 (general/hr/accounting/it/legal)
- `test:effectiveDate` (DateTime): 施行日
- `test:reviewDate` (DateTime): 次回見直し日
- `test:policyVersion` (String): 版数

## Troubleshooting

### Connection Issues
- Verify NemakiWare is running and accessible
- Check CMIS URL and credentials in config.yaml
- Ensure the repository ID matches your NemakiWare configuration

### Permission Errors
- Use admin credentials for initial setup
- Verify the admin user has full permissions

### Type Registration Failures
- Types may already exist if running the script multiple times
- Check NemakiWare logs for detailed error messages
