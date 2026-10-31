import axios from "axios";

const api = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL,
    headers: {
        "Content-Type": "application/json"
    }

});

export default api;

export const createWorkflow = async (workflow) => {
    const response = await api.post("/api/create-workflow", workflow);
    return response.data;
};

export const saveWorkflowGraph = async (workflowId, graph) => {

    const response = await api.put(`/api/workflow/${workflowId}/graph`, graph);
    return response.data;
};

export const getWorkflow = async (workflowId) => {

    const response = await api.get(`/api/workflow/${workflowId}`);
    return response.data;
};


export const getWorkflowNodes =  async (workflowId) => {

    const response = await api.get(`/api/workflow/${workflowId}/nodes`);
    return response.data;
};

export const getWorkflowConnections =  async (workflowId) => {

    const response = await api.get(`/api/workflow/${workflowId}/connections`);
    return response.data;
};

export const startWorkflowExecution = async (workflowId) => {
    const response = await api.post(`/api/workflow/${workflowId}/execution`);
    return response.data;
}

export const getExecution = async (executionId) => {
    const response = await api.get(`/api/execution/${executionId}`);
    return response.data;
}

export const getNodeExecution = async (executionId) => {
    const response = await api.get(`/api/execution/${executionId}/nodes`);
    return response.data;
}
