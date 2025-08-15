import axios from 'axios';
import { ActionDefinition, ActionForm, ActionExecutionResult } from '../types/cmis';
import { REST_BASE } from '../config';

export class ActionService {
  private baseUrl: string;

  constructor() {
    this.baseUrl = REST_BASE;
  }

  async discoverActions(repositoryId: string, objectId: string): Promise<ActionDefinition[]> {
    try {
      const response = await axios.get(
        `${this.baseUrl}/repo/${repositoryId}/actions/discover/${objectId}`,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );
      return response.data;
    } catch (error) {
      console.error('Error discovering actions:', error);
      throw error;
    }
  }

  async getActionForm(repositoryId: string, actionId: string, objectId: string): Promise<ActionForm> {
    try {
      const response = await axios.get(
        `${this.baseUrl}/repo/${repositoryId}/actions/${actionId}/form/${objectId}`,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );
      return response.data;
    } catch (error) {
      console.error('Error getting action form:', error);
      throw error;
    }
  }

  async executeAction(
    repositoryId: string, 
    actionId: string, 
    objectId: string, 
    formData: Record<string, any>
  ): Promise<ActionExecutionResult> {
    try {
      const response = await axios.post(
        `${this.baseUrl}/repo/${repositoryId}/actions/${actionId}/execute/${objectId}`,
        formData,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`,
            'Content-Type': 'application/json'
          }
        }
      );
      return response.data;
    } catch (error) {
      console.error('Error executing action:', error);
      throw error;
    }
  }
}
