import React, { useState, useEffect } from 'react';
import { Editor } from '@monaco-editor/react';
import { Spin, Alert } from 'antd';

interface TextPreviewProps {
  url: string;
  fileName: string;
}

export const TextPreview: React.FC<TextPreviewProps> = ({ url, fileName }) => {
  const [content, setContent] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(url)
      .then(response => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return response.text();
      })
      .then(text => {
        setContent(text);
        setLoading(false);
      })
      .catch(() => {
        setError('ファイルの読み込みに失敗しました');
        setLoading(false);
      });
  }, [url]);

  const getLanguage = () => {
    const ext = fileName.split('.').pop()?.toLowerCase();
    const languageMap: Record<string, string> = {
      js: 'javascript', 
      ts: 'typescript', 
      tsx: 'typescript',
      jsx: 'javascript',
      py: 'python', 
      java: 'java',
      html: 'html', 
      css: 'css', 
      json: 'json', 
      xml: 'xml', 
      md: 'markdown',
      txt: 'plaintext',
      log: 'plaintext',
      yml: 'yaml',
      yaml: 'yaml',
      sql: 'sql',
      sh: 'shell',
      bash: 'shell'
    };
    return languageMap[ext || ''] || 'plaintext';
  };

  if (loading) return <Spin size="large" style={{ display: 'block', textAlign: 'center', padding: '50px' }} />;
  
  if (error) return <Alert message="エラー" description={error} type="error" />;

  return (
    <div>
      <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>
      <Editor
        height="500px"
        language={getLanguage()}
        value={content}
        options={{ 
          readOnly: true, 
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
          automaticLayout: true
        }}
        theme="vs-light"
      />
    </div>
  );
};
