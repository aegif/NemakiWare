#!/usr/bin/env node
/**
 * Post-build script to synchronize callback HTML files with index.html asset references
 *
 * Problem:
 * - Vite generates hashed asset filenames (e.g., index-CNWT62pH.js)
 * - index.html is auto-updated by Vite with correct asset references
 * - oidc-callback.html and saml-callback.html are static files that don't get updated
 * - This causes OIDC/SAML authentication to fail after each build
 *
 * Solution:
 * - Extract JS and CSS asset references from dist/index.html
 * - Update dist/oidc-callback.html and dist/saml-callback.html with matching references
 *
 * Usage:
 * - Run after `vite build`: node scripts/sync-callback-assets.js
 * - Or use in package.json: "build": "vite build && node scripts/sync-callback-assets.js"
 */

const fs = require('fs');
const path = require('path');

const distDir = path.join(__dirname, '..', 'dist');
const indexPath = path.join(distDir, 'index.html');

// Callback files to update
const callbackFiles = ['oidc-callback.html', 'saml-callback.html'];

function extractAssetRefs(indexHtml) {
  // Extract JS asset reference
  const jsMatch = indexHtml.match(/<script[^>]*src="([^"]*\/assets\/index-[^"]+\.js)"[^>]*>/);
  const jsPath = jsMatch ? jsMatch[1] : null;

  // Extract CSS asset reference
  const cssMatch = indexHtml.match(/<link[^>]*href="([^"]*\/assets\/index-[^"]+\.css)"[^>]*>/);
  const cssPath = cssMatch ? cssMatch[1] : null;

  return { jsPath, cssPath };
}

function updateCallbackFile(filePath, jsPath, cssPath) {
  let content = fs.readFileSync(filePath, 'utf-8');

  // Update JS asset reference
  if (jsPath) {
    content = content.replace(
      /<script[^>]*src="[^"]*\/assets\/index-[^"]+\.js"[^>]*>/g,
      `<script type="module" crossorigin src="${jsPath}">`
    );
  }

  // Update CSS asset reference
  if (cssPath) {
    content = content.replace(
      /<link[^>]*href="[^"]*\/assets\/index-[^"]+\.css"[^>]*>/g,
      `<link rel="stylesheet" crossorigin href="${cssPath}">`
    );
  }

  fs.writeFileSync(filePath, content, 'utf-8');
  return true;
}

function main() {
  console.log('🔄 Syncing callback HTML files with index.html assets...\n');

  // Check if dist/index.html exists
  if (!fs.existsSync(indexPath)) {
    console.error('❌ Error: dist/index.html not found. Run "vite build" first.');
    process.exit(1);
  }

  // Read index.html and extract asset references
  const indexHtml = fs.readFileSync(indexPath, 'utf-8');
  const { jsPath, cssPath } = extractAssetRefs(indexHtml);

  console.log('📦 Extracted asset references from index.html:');
  console.log(`   JS:  ${jsPath || '(not found)'}`);
  console.log(`   CSS: ${cssPath || '(not found)'}\n`);

  if (!jsPath) {
    console.error('❌ Error: Could not find JS asset reference in index.html');
    process.exit(1);
  }

  // Update each callback file
  let updatedCount = 0;
  for (const fileName of callbackFiles) {
    const filePath = path.join(distDir, fileName);

    if (!fs.existsSync(filePath)) {
      console.log(`⚠️  Skipping ${fileName} (not found in dist/)`);
      continue;
    }

    try {
      updateCallbackFile(filePath, jsPath, cssPath);
      console.log(`✅ Updated ${fileName}`);
      updatedCount++;
    } catch (error) {
      console.error(`❌ Error updating ${fileName}: ${error.message}`);
    }
  }

  console.log(`\n✨ Done! Updated ${updatedCount}/${callbackFiles.length} callback files.`);
}

main();
