import React, { useState } from 'react';
import { Layout as AntLayout, Menu, Button, Dropdown, Avatar, Space } from 'antd';
import { 
  FileOutlined, 
  SearchOutlined, 
  UserOutlined, 
  TeamOutlined, 
  SettingOutlined,
  LogoutOutlined,
  FolderOutlined,
  SecurityScanOutlined,
  InboxOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';

const { Header, Sider, Content } = AntLayout;

interface LayoutProps {
  children: React.ReactNode;
  repositoryId: string;
}

export const Layout: React.FC<LayoutProps> = ({ children, repositoryId }) => {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { logout, authToken } = useAuth();

  const menuItems = [
    {
      key: '/documents',
      icon: <FolderOutlined />,
      label: 'ドキュメント',
    },
    {
      key: '/search',
      icon: <SearchOutlined />,
      label: '検索',
    },
    {
      key: 'admin',
      icon: <SettingOutlined />,
      label: '管理',
      children: [
        {
          key: '/users',
          icon: <UserOutlined />,
          label: 'ユーザー管理',
        },
        {
          key: '/groups',
          icon: <TeamOutlined />,
          label: 'グループ管理',
        },
        {
          key: '/types',
          icon: <FileOutlined />,
          label: 'タイプ管理',
        },
        {
          key: '/archive',
          icon: <InboxOutlined />,
          label: 'アーカイブ',
        },
      ],
    },
  ];

  const handleMenuClick = ({ key }: { key: string }) => {
    if (key !== 'admin') {
      navigate(key);
    }
  };

  const handleLogout = () => {
    console.log('Layout: handleLogout called - using AuthContext logout');
    logout();
  };

  const userMenuItems = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: 'ログアウト',
      onClick: handleLogout,
    },
  ];

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Sider 
        trigger={null} 
        collapsible 
        collapsed={collapsed}
        style={{
          background: '#fff',
          borderRight: '1px solid #f0f0f0'
        }}
      >
        <div style={{ 
          height: 64, 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'center',
          borderBottom: '1px solid #f0f0f0',
          background: '#cccccc'
        }}>
          {!collapsed && (
            <h3 style={{ color: '#c72439', margin: 0, fontWeight: 'bold' }}>
              NemakiWare
            </h3>
          )}
          {collapsed && (
            <div style={{ color: '#c72439', fontSize: '18px', fontWeight: 'bold' }}>
              N
            </div>
          )}
        </div>
        
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={handleMenuClick}
          style={{ borderRight: 0 }}
        />
      </Sider>
      
      <AntLayout>
        <Header style={{ 
          padding: '0 16px', 
          background: '#fff', 
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between'
        }}>
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
            style={{ fontSize: '16px', width: 64, height: 64 }}
          />
          
          <Space>
            <span style={{ color: '#666' }}>
              Repository: <strong>{repositoryId}</strong>
            </span>
            <Dropdown
              menu={{ items: userMenuItems }}
              placement="bottomRight"
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar icon={<UserOutlined />} />
                <span>{authToken?.username}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        
        <Content style={{ 
          margin: '16px', 
          padding: '16px',
          background: '#fff',
          borderRadius: '6px',
          minHeight: 'calc(100vh - 112px)'
        }}>
          {children}
        </Content>
      </AntLayout>
    </AntLayout>
  );
};
