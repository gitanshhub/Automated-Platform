import api, { createWorkflow, saveWorkflowGraph, getWorkflow, getWorkflowConnections, getWorkflowNodes, startWorkflowExecution, getExecution, getNodeExecution } from "./api";


import { useCallback, useEffect, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { json } from "@codemirror/lang-json";
import { closeBrackets } from "@codemirror/autocomplete";
import { EditorView } from "@codemirror/view";
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Handle,
  Position,
  addEdge,
  useNodesState,
  useEdgesState,
} from "@xyflow/react";
import {
  Workflow,
  Plus,
  Bell,
  CircleHelp,
  Edit3,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings2,
  GitBranch,
  Globe,
  Braces,
  Clock3,
  Zap,
  Mail,
  MessageCircle,
  ChevronDown,
  ChevronRight,
  X,
  Trash2,
  Play,
  LayoutDashboard,
  History,
  UserRound,
} from "lucide-react";
import "@xyflow/react/dist/style.css";
import "./index.css";
import { backendNodeTypes, frontendNodeTypes, nodeDefinitions } from "./nodeDefinitions";
import delayLogo from "./assets/logos/delay.png";
import gmailLogo from "./assets/logos/google-mail-gmail-icon-logo-symbol-free-png.webp";
import httpLogo from "./assets/logos/http.png";
import ifLogo from "./assets/logos/if.png";
import manualLogo from "./assets/logos/maual trigger.png";
import mergeLogo from "./assets/logos/merge.png";
import transformLogo from "./assets/logos/Purple Code Transform Icon.png";
import telegramLogo from "./assets/logos/Telegram_2019_Logo.svg.webp";
import webhookLogo from "./assets/logos/webhook.png";

const nodeCatalog = [
  { type: "manual", label: nodeDefinitions.manual.label, group: "Triggers", icon: Zap, logo: manualLogo, color: "orange" },
  { type: "http", label: nodeDefinitions.http.label, group: "Actions", icon: Globe, logo: httpLogo, color: "blue" },
  { type: "delay", label: nodeDefinitions.delay.label, group: "Actions", icon: Clock3, logo: delayLogo, color: "yellow" },
  { type: "if", label: nodeDefinitions.if.label, group: "Logic", icon: GitBranch, logo: ifLogo, color: "purple" },
  { type: "transform", label: nodeDefinitions.transform.label, group: "Data", icon: Braces, logo: transformLogo, color: "green" },
  { type: "gmail", label: nodeDefinitions.gmail.label, group: "Integrations", icon: Mail, logo: gmailLogo, color: "red" },
  { type: "telegram", label: nodeDefinitions.telegram.label, group: "Integrations", icon: MessageCircle, logo: telegramLogo, color: "cyan" },
  { type: "webhook", label: nodeDefinitions.webhook.label, group: "Triggers", icon: Globe, logo: webhookLogo, color: "orange" },
  { type: "merge", label: nodeDefinitions.merge.label, group: "Logic", icon: GitBranch, logo: mergeLogo, color: "purple" }
];

const starterTemplates = [
  { name: "AI → Telegram", description: "Ask a model and send the answer", nodes: ["manual", "http", "transform", "telegram"] },
  { name: "Webhook → Gmail", description: "Receive data and email a report", nodes: ["webhook", "transform", "gmail"] },
  { name: "Manual → HTTP", description: "Run an API request on demand", nodes: ["manual", "http"] }
];

function WorkflowNode({ data, selected }) {
  const item = nodeCatalog.find((node) => node.type === data.nodeType);
  const Icon = item?.icon ?? Workflow;

  return (
    <div className={`workflow-node ${selected ? "node-selected" : ""}`}>
      
      {!["manual", "webhook"].includes(data.nodeType) &&
       <Handle 
          type="target" position={Position.Left} className="node-handle" 
       />}

      <div className={`node-icon ${data.color} logo-${data.nodeType}`}>{item?.logo ? <img src={item.logo} alt="" /> : <Icon size={18} />}</div>
      <div className="node-copy">
        <div className="node-title">{data.label}</div>
        <div className="node-subtitle">{data.nodeType}</div>
      </div>
      {data.nodeType === "if" ? (
        <>
          <Handle type="source" id="true" position={Position.Right} style={{ top: "35%" }} className="node-handle" />
          <Handle type="source" id="false" position={Position.Right} style={{ top: "65%" }} className="node-handle" />
        </>
      ) : data.nodeType !== "gmail" && data.nodeType !== "telegram" && (
        <Handle type="source" position={Position.Right} className="node-handle" />
      )}
    </div>
  );
}

function InspectorDivider({ onMouseDown }) {
  return (
    <div
      className="inspector-divider"
      role="separator"
      aria-label="Resize panel"
      onPointerDown={onMouseDown}
    />
  );
}

function JsonEditor({ value, onChange, placeholder }) {
  const text = typeof value === "string"
    ? value
    : JSON.stringify(value ?? {}, null, 2);

  return (
    <div className="json-editor">
      <div className="json-editor-toolbar">
        <span>JSON</span>
      </div>
      <CodeMirror
        value={text}
        placeholder={placeholder}
        height="220px"
        theme="dark"
        extensions={[json(), closeBrackets(), EditorView.lineWrapping]}
        basicSetup={{
          lineNumbers: true,
          foldGutter: true,
          bracketMatching: true,
          closeBrackets: true,
          autocompletion: true,
          indentOnInput: true
        }}
        onChange={onChange}
      />
    </div>
  );
}

const nodeTypes = { workflowNode: WorkflowNode };
const groups = ["Triggers", "Actions", "Logic", "Data", "Integrations"];

const getNodeColor = (backendType) => {
  const colors = {
    MANUAL_TRIGGER: "orange",
    WEBHOOK: "red",
    HTTP_REQUEST: "blue",
    DELAY: "yellow",
    IF: "purple",
    TRANSFORM: "green",
    MERGE: "purple",
    TELEGRAM: "cyan",
    GMAIL: "maroon"
  }
  return colors[backendType] ?? "blue";
};

const toReactFlowNode = (node) => ({
  id: String(node.id),
  type: "workflowNode",
  position: node.configuration?.position ?? {
    x: 100,
    y: 100
  },

  data: {
    nodeType: frontendNodeTypes[node.type],
    label: node.name,
    color: getNodeColor(node.type),
    config: node.configuration ?? {}
  }
});


function App() {
  const [workflowId, setWorkflowId] = useState(null);
  const [saving, setSaving] = useState(false);
  const [isDirty, setIsDirty] = useState(false);
  const [execution, setExecution] = useState(null);
  const [nodeExecutions, setNodeExecutions] = useState([]);
  const [inputPanelWidth, setInputPanelWidth] = useState(250);
  const [outputPanelWidth, setOutputPanelWidth] = useState(290);
  const [running, setRunning] = useState(false);


  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selectedNode, setSelectedNode] = useState(null);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [search, setSearch] = useState("");
  const [workflowName, setWorkflowName] = useState("Untitled workflow");
  const [editingName, setEditingName] = useState(false);
  const [collapsedGroups, setCollapsedGroups] = useState({});
  const [notice, setNotice] = useState("");
  const [activeSection, setActiveSection] = useState("Editor");

  const [workflows, setWorkflows] = useState([]);
  const [loadingWorkflows, setLoadingWorkflows] = useState(false)

  useEffect(() => {
    if (!notice) return undefined;

    const timeoutId = window.setTimeout(() => setNotice(""), 5000);
    return () => window.clearTimeout(timeoutId);
  }, [notice]);

  const startInspectorResize = (panel, event) => {
    event.preventDefault();
    event.currentTarget?.setPointerCapture?.(event.pointerId);
    const startX = event.clientX;
    const initialWidth = panel === "input" ? inputPanelWidth : outputPanelWidth;

    const move = (moveEvent) => {
      const delta = moveEvent.clientX - startX;
      const nextWidth = Math.max(190, Math.min(460, initialWidth + (panel === "input" ? delta : -delta)));

      if (panel === "input") setInputPanelWidth(nextWidth);
      else setOutputPanelWidth(nextWidth);
    };

    const stop = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", stop);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };

    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", stop);
  };

  const parseJsonField = (value, fieldName) => {
    if (!value || !String(value).trim()) return {};
    if (typeof value === "object") return value;
    try {
      return JSON.parse(value);
    } catch {
      throw new Error(`${fieldName} must contain valid JSON.`);
    }
  };


  const buildTransformConfiguration = (config) => {
    const existingOperation = config.operations?.[0];
    const operationType = config.operationType ?? existingOperation?.type;
    const hasEditedOperation = Boolean(
      config.operationType || config.sourceField || config.targetField ||
      config.operationValue !== undefined || config.selectedFields || config.mappings
    );

    if (!hasEditedOperation && Array.isArray(config.operations)) return { ...config };

    switch (operationType ?? "SET_FIELD") {
      case "SELECT_FIELD":
        return { ...config, operations: [{ type: "SELECT_FIELD", field: config.selectedFields ?? [] }] };
      case "MAP_FIELDS":
        return { ...config, operations: [{ type: "MAP_FIELDS", mappings: parseJsonField(config.mappings ?? {}, "Transform mappings") }] };
      case "SET_FIELD":
        return { ...config, operations: [{ type: "SET_FIELD", targetField: config.targetField, value: config.operationValue }] };
      case "RENAME":
        return { ...config, operations: [{ type: "RENAME", sourceField: config.sourceField, targetField: config.targetField }] };
      case "REMOVE_FIELD":
        return { ...config, operations: [{ type: "REMOVE_FIELD", sourceField: config.sourceField }] };
      case "UPPERCASE":
      case "LOWERCASE":
      case "TRIM":
        return { ...config, operations: [{ type: operationType, sourceField: config.sourceField, ...(config.targetField ? { targetField: config.targetField } : {}) }] };
      case "EXTRACT_DOMAIN":
        return { ...config, operations: [{ type: "EXTRACT_DOMAIN", sourceField: config.sourceField, targetField: config.targetField }] };
      default:
        return { ...config };
    }
  };

  const buildWorkflowGraph = () => ({
    name: workflowName,
    description: "",
    nodes: nodes.map((node) => ({
      id: Number.isNaN(Number(node.id)) ? null : Number(node.id),
      clientId: node.id,
      name: node.data.label,
      type: backendNodeTypes[node.data.nodeType],

      configuration: {
        ...node.data.config,
        ...(node.data.nodeType === "http" && {
          method: String(node.data.config.method ?? "GET").toUpperCase(),
          headers: parseJsonField(node.data.config.headers, "HTTP headers"),
          body: parseJsonField(node.data.config.body, "HTTP body")
        }),
        ...(node.data.nodeType === "transform" && buildTransformConfiguration(node.data.config)),
        ...(node.data.nodeType === "gmail" && {
          body: node.data.config.body ?? node.data.config.message ?? ""
        }),
        position: node.position
      }
    })),

    connections: edges.map((edge) => ({
      sourceClientId: edge.source,
      targetClientId: edge.target,
      branch: edge.label ?? null
    }))

  });


  const saveWorkflow = async () => {
    if (!workflowId) {
      setNotice("No workflow is currently open.");
      return false;
    }

    try {
      setSaving(true);

      const savedGraph = await saveWorkflowGraph(workflowId, buildWorkflowGraph());

      setNodes(savedGraph.nodes.map(toReactFlowNode));

      setEdges(savedGraph.connections.map((connection) => ({
        id: String(connection.id),
        source: String(connection.sourceNode.id),
        target: String(connection.targetNode.id),
        label: connection.branch ?? undefined
      }))
      );

      setNotice("Workflow saved to PostgreSQL.");
      setIsDirty(false);
      return true;

    } catch (error) {
      console.error("Failed to save workflow: ", error);
      setNotice(error.response?.data?.message ?? "Failed to save workflow.");
      return false;

    } finally {
      setSaving(false);
    }
  };


  const fetchWorkflows = async () => {
    try {
      setLoadingWorkflows(true);

      const response = await api.get("/api/workflows");

      console.log("workflow from backend: ", response.data);

      setWorkflows(response.data);
    } catch (error) {
      console.error("Failed to fetch workflow: ", error);
      setNotice("Failed to load the workflow from the backend.");

    } finally {
      setLoadingWorkflows(false);
    }
  };

  const onConnect = useCallback(
    (connection) => {
      setEdges((current) => addEdge({
        ...connection,
        label: connection.sourceHandle ?? undefined
      }, current));
      setIsDirty(true);
    },
    [setEdges]
  );

  const handleNodesChange = useCallback((changes) => {
    onNodesChange(changes);
    setIsDirty(true);
  }, [onNodesChange]);

  const handleEdgesChange = useCallback((changes) => {
    onEdgesChange(changes);
    setIsDirty(true);
  }, [onEdgesChange]);

  const createNewWorkflow = async () => {
    try {
      const workflow = await createWorkflow({
        name: "Untitled workflow",
        description: "",
        status: "ACTIVE"
      });

      setWorkflowId(workflow.id);
      setWorkflowName(workflow.name);
      setNodes([]);
      setEdges([]);
      setSelectedNode(null);
      setIsDirty(false);
      setNotice("New workflow created.");
    } catch (error) {
      console.error("Failed to create workflow:", error);
      setNotice(error.response?.data?.message ?? "Failed to create workflow.");
    }
  };

  const addNode = async (item) => {
    if (!workflowId) {
      try {
        const workflow = await createWorkflow({
          name: "Untitled workflow",
          description: "",
          status: "ACTIVE"
        });

        setWorkflowId(workflow.id);
        setWorkflowName(workflow.name);
        setNotice("Workflow created.");
      } catch (error) {
        console.error("Failed to create workflow:", error);
        setNotice(error.response?.data?.message ?? "Failed to create workflow.");
        return;
      }
    }

    const id = crypto.randomUUID();
    const newNode = {
      id,
      type: "workflowNode",
      position: { x: 120 + (nodes.length % 4) * 180, y: 100 + Math.floor(nodes.length / 4) * 140 },
      data: {
        nodeType: item.type,
        label: item.label,
        color: item.color,
        config: {},
      },
    };
    setNodes((current) => [...current, newNode]);
    setIsDirty(true);
  };

  const addStarterWorkflow = async (template) => {
    let activeWorkflowId = workflowId;
    if (!activeWorkflowId) {
      try {
        const workflow = await createWorkflow({ name: template.name, description: template.description, status: "ACTIVE" });
        activeWorkflowId = workflow.id;
        setWorkflowId(workflow.id);
        setWorkflowName(workflow.name);
      } catch (error) {
        console.error("Failed to create starter workflow:", error);
        setNotice(error.response?.data?.message ?? "Failed to create workflow.");
        return;
      }
    }

    const createdNodes = template.nodes.map((type, index) => {
      const item = nodeCatalog.find((node) => node.type === type);
      return {
        id: crypto.randomUUID(),
        type: "workflowNode",
        position: { x: 100 + index * 240, y: 180 },
        data: { nodeType: item.type, label: item.label, color: item.color, config: {} }
      };
    });
    setNodes(createdNodes);
    setEdges(createdNodes.slice(1).map((node, index) => ({
      id: `starter-${createdNodes[index].id}-${node.id}`,
      source: createdNodes[index].id,
      target: node.id,
      type: "default"
    })));
    setSelectedNode(null);
    setIsDirty(true);
    setNotice(`${template.name} starter workflow added.`);
  };

  const updateSelectedNode = (changes) => {
    if (!selectedNode) return;
    const updatedNode = {
      ...selectedNode,
      data: { ...selectedNode.data, ...changes },
    };
    setNodes((current) => current.map((node) => node.id === selectedNode.id ? updatedNode : node));
    setSelectedNode(updatedNode);
    setIsDirty(true);
  };

  const updateConfig = (key, value) => {
    updateSelectedNode({
      config: { ...(selectedNode?.data.config ?? {}), [key]: value },
    });
  };

  const deleteSelectedNode = () => {
    if (!selectedNode) return;
    setNodes((current) => current.filter((node) => node.id !== selectedNode.id));
    setEdges((current) => current.filter((edge) => edge.source !== selectedNode.id && edge.target !== selectedNode.id));
    setSelectedNode(null);
    setIsDirty(true);
  };

  const runWorkflow = async () => {
    if (!workflowId) {
      setNotice("Create or open a workflow before running it.");
      return;
    }

    if (nodes.length === 0) {
      setNotice("Add at least one node before running the workflow.");
      return;
    }

    try {
      setRunning(true);
      if (isDirty) {
        setNotice("Saving workflow...");

        const saved = await saveWorkflow();
        if (!saved) {
          setRunning(false);
          return;
        }
      }

      setNotice("Starting workflow execution...");

      const startedExecution = await startWorkflowExecution(workflowId);

      setExecution(startedExecution);
      setNotice(`Execution ${startedExecution.id} started with status ${startedExecution.status}.`);

      pollExecution(startedExecution.id);

    } catch (error) {
      console.error("Failed to run workflow: ", error);
      setNotice("Failed to run workflow execution.");
      setRunning(false);
    }
  };
  const wait = (ms) => new Promise((resolve) => {
      setTimeout(resolve, ms);
    });

  const pollExecution = async (executionId) => {
    setRunning(true);

    try {
      setRunning(true);
      
      while (true) {
        const currentExecution = await getExecution(executionId);

        setExecution(currentExecution);

        const finishedStatuses = ["SUCCESS", "FAILED"];

        if (finishedStatuses.includes(currentExecution.status)) {
          const nodeResults = await getNodeExecution(executionId);
          setNodeExecutions(nodeResults);
          setRunning(false);
          setNotice(`Execution finished  with status ${currentExecution.status}`);
          
          break;
        }

        await wait(2000);
      }
    } catch(error) {
      console.error("Failed to feth execution status: ", error);
      setRunning(false);
      setNotice("Could not retrieve execution status.");
    }
  };


  const chooseSection = (section) => {
    console.log("clicked section: ", section);

    setSelectedNode(null);
    setActiveSection(section);

    if (section === "Workflows") {
      console.log("fetching workflow...");
      fetchWorkflows();

    }

    if (section === "Executions" || section === "Settings") {
      setNotice(`${section} section is not connected yet.`);
    }
  };

  const filteredNodes = nodeCatalog.filter((item) =>
    item.label.toLowerCase().includes(search.toLowerCase())
  );

  const loadWorkflow = async (id) => {
    try {
      setLoadingWorkflows(true);

      const [workflow, backendNodes, backendConnections] = await Promise.all([getWorkflow(id), getWorkflowNodes(id), getWorkflowConnections(id)]);
      setWorkflowId(id);
      setWorkflowName(workflow.name);

      setNodes(backendNodes.map(toReactFlowNode));
      setEdges(backendConnections.map((connection) => ({
        id: String(connection.id),
        source: String(connection.sourceNode.id),
        target: String(connection.targetNode.id),
        label: connection.branch ?? undefined
      }))
      );

      setIsDirty(false);
      setNotice("Workflow loaded from PostgreSQL.");

    } catch (error) {
      console.error("Failed to load workflow: ", error);
      setNotice("Failed to load workflow.");

    } finally {
      setLoadingWorkflows(false);
    }
  };
  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark"><Workflow size={19} /></div>
          <span>Flowforge</span>
        </div>
        <div className="workflow-heading">
          {editingName ? (
            <input
              className="workflow-name-input"
              value={workflowName}
              autoFocus
              onChange={(event) => {
                setWorkflowName(event.target.value);
                setIsDirty(true);
              }}
              onBlur={() => setEditingName(false)}
              onKeyDown={(event) => event.key === "Enter" && setEditingName(false)}
            />
          ) : (
            <>
              <span className="workflow-context">Workflows <ChevronRight size={13} /></span>
              <button className="workflow-name" onClick={() => setEditingName(true)} title="Rename workflow">
                {workflowName}<Edit3 size={13} />
              </button>
              <span className="saved-pill"><span /> Saved</span>
            </>
          )}
        </div>
        <div className="topbar-actions">
          <button className="topbar-icon-button" title="Help"><CircleHelp size={16} /></button>
          <button className="topbar-icon-button notification-button" title="Notifications"><Bell size={16} /><span /></button>
          <div className="topbar-avatar">AS</div>
          <button className="run-button" onClick={runWorkflow} disabled={running || saving}><Play size={14} /> {running ? (saving ? "Saving and running..." : "Running...") : "Run"} <ChevronDown size={13} /></button>
          <button
            className="new-workflow-button"
            onClick={createNewWorkflow}
          >
            <Plus size={16} /> New workflow
          </button>
        </div>
      </header>

      <div className="workspace">
        <aside className="left-sidebar workspace-sidebar">
          <div className="sidebar-header"><span>WORKSPACE</span></div>
          <div className="sidebar-navigation sidebar-navigation-top">
            <button className={`sidebar-nav-item ${activeSection === "Editor" ? "active" : ""}`} onClick={() => chooseSection("Editor")}><Workflow size={16} /><span>Editor</span></button>
            <button className={`sidebar-nav-item ${activeSection === "Workflows" ? "active" : ""}`} onClick={() => chooseSection("Workflows")}><LayoutDashboard size={16} /><span>Workflows</span></button>
            <button className={`sidebar-nav-item ${activeSection === "Executions" ? "active" : ""}`} onClick={() => chooseSection("Executions")}><History size={16} /><span>Executions</span></button>
            <button className={`sidebar-nav-item ${activeSection === "Settings" ? "active" : ""}`} onClick={() => chooseSection("Settings")}><Settings2 size={16} /><span>Settings</span></button>
          </div>
          <div className="profile-footer">
            <div className="profile-avatar"><UserRound size={17} /></div>
            <div className="profile-copy"><strong>Developer</strong><span>Local workspace</span></div>
            <span className="profile-status" title="Local frontend"></span>
          </div>
        </aside>

        <main className="canvas-area">
          {activeSection === "Workflows" ? (
            <div className="workflow-list-panel">
              <div className="canvas-toolbar">
                <span className="canvas-title">Saved workflows</span>
                <button className="secondary-button" onClick={fetchWorkflows} disabled={loadingWorkflows}>
                  {loadingWorkflows ? "Loading..." : "Refresh"}
                </button>
              </div>
              <div className="workflow-list">
                {!loadingWorkflows && workflows.length === 0 && (
                  <p className="empty-search">No workflows found.</p>
                )}
                {workflows.map((workflow) => (
                  <button
                    key={workflow.id}
                    className="workflow-list-item"
                    onClick={() => {
                      loadWorkflow(workflow.id);
                      setActiveSection("Editor");
                    }}
                  >
                    <span>
                      <strong>{workflow.name || "Untitled workflow"}</strong>
                      <small>Workflow #{workflow.id}</small>
                    </span>
                    <span>{workflow.status || "UNKNOWN"}</span>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <>
          <div className="canvas-toolbar">
            <div className="canvas-heading"><span className="canvas-title">Canvas</span><span>Build your automation visually</span></div>
            <div className="canvas-toolbar-actions"><span className="node-count-label">{nodes.length} nodes · {edges.length} connections</span><button className="canvas-tool-button" onClick={() => setSidebarOpen(true)}><Plus size={14} /> Add node</button></div>
            {execution && (
              <div className="execution-status">
                <strong>Execution:</strong>{" "}
                {execution.id} - {execution.status}
              </div>
              )}
          </div>
          <div className="flow-canvas">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              onNodesChange={handleNodesChange}
              onEdgesChange={handleEdgesChange}
              onConnect={onConnect}
              onNodeClick={(_, node) => setSelectedNode(node)}
              onPaneClick={() => setSelectedNode(null)}
              fitView
              deleteKeyCode={["Backspace", "Delete"]}
              defaultEdgeOptions={{ style: { stroke: "#64748b", strokeWidth: 1.5 } }}
            >
              <Background color="#293140" gap={22} size={1} />
              <Controls position="bottom-left" />
              <MiniMap
                position="bottom-right"
                pannable
                zoomable
                nodeColor={(node) => {
                  const colors = { manual: "#f5b84e", http: "#77b5ff", delay: "#f4d477", if: "#bba2ff", transform: "#70d8a0", gmail: "#ff9898", telegram: "#78d9ed" };
                  return colors[node.data?.nodeType] ?? "#8b95a7";
                }}
                maskColor="rgba(7, 10, 16, 0.72)"
                style={{ background: "#141923", border: "1px solid #303746", borderRadius: 8 }}
              />
            </ReactFlow>
            {nodes.length === 0 && (
              <div className="empty-canvas">
                <div className="empty-graph-illustration" aria-hidden="true"><span><Zap size={14} /></span><i /><span><Braces size={14} /></span><i /><span><Globe size={14} /></span></div>
                <span className="empty-eyebrow">NEW WORKFLOW</span>
                <h3>Start with an empty canvas</h3>
                <p>Build powerful workflows by connecting triggers, actions, and logic.</p>
                <div className="starter-template-grid">{starterTemplates.map((template) => <button className="starter-template" key={template.name} onClick={() => addStarterWorkflow(template)}><span><strong>{template.name}</strong><small>{template.description}</small></span><ChevronRight size={14} /></button>)}</div>
                <button className="new-workflow-button" onClick={() => setSidebarOpen(true)}><Plus size={15} /> Browse nodes</button>
              </div>
            )}
          </div>
            </>
          )}
        </main>

        <aside className={`right-node-library ${sidebarOpen ? "" : "right-node-library-collapsed"}`}>
          <div className="sidebar-header"><span>{sidebarOpen && "NODE LIBRARY"}</span><button className="icon-button" onClick={() => setSidebarOpen((open) => !open)} title="Toggle node library">{sidebarOpen ? <PanelLeftClose size={17} /> : <PanelLeftOpen size={17} />}</button></div>
          {sidebarOpen ? <>
            <div className="node-search"><Search size={15} /><input placeholder="Search nodes..." value={search} onChange={(event) => setSearch(event.target.value)} /></div>
            <div className="node-list">{groups.map((group) => { const items = filteredNodes.filter((item) => item.group === group); if (!items.length) return null; const collapsed = collapsedGroups[group]; return <div className="node-group" key={group}><button className="node-group-heading" onClick={() => setCollapsedGroups((current) => ({ ...current, [group]: !current[group] }))}>{collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}{group}<span>{items.length}</span></button>{!collapsed && items.map((item) => { const Icon = item.icon; return <button key={item.type} className="library-node" onClick={() => addNode(item)}><span className={`library-node-icon ${item.color} logo-${item.type}`}>{item.logo ? <img src={item.logo} alt="" /> : <Icon size={15} />}</span><span>{item.label}</span><Plus size={14} className="library-add" /></button>; })}</div>; })}{filteredNodes.length === 0 && <p className="empty-search">No nodes found.</p>}</div>
            <button className="browse-nodes-button" onClick={() => setSearch("")}><Plus size={14} /> Browse all nodes <ChevronRight size={13} /></button>
          </> : <div className="collapsed-icons"><button title="Open node library" onClick={() => setSidebarOpen(true)}><Plus size={19} /></button></div>}
        </aside>

        {selectedNode && (
          <div className="node-inspector">
            {(() => {
              const nodeResult = nodeExecutions.find((result) => String(result.nodeId) === String(selectedNode.id));
              const upstreamIds = edges
                .filter((edge) => String(edge.target) === String(selectedNode.id))
                .map((edge) => String(edge.source));
              const upstreamResults = nodeExecutions.filter((result) => upstreamIds.includes(String(result.nodeId)));
              const inputStatus = upstreamResults.some((result) => result.status === "FAILED")
                ? "FAILED"
                : upstreamResults.some((result) => result.status === "RUNNING")
                  ? "RUNNING"
                  : upstreamResults.length > 0 && upstreamResults.every((result) => result.status === "SUCCESS")
                    ? "SUCCESS"
                    : null;
              const inputItems = nodeResult?.outputData?.inputItems ?? [];
              return (
                <section className="inspector-side-panel inspector-input-panel" style={{ width: inputPanelWidth }}>
                  <div className="inspector-side-header"><span>Input</span><span className={`execution-pill ${inputStatus?.toLowerCase() ?? "idle"}`}>{inputStatus ?? "NOT RUN"}</span></div>
                  <div className="inspector-side-content">
                    {nodeResult ? <pre className="data-preview">{JSON.stringify(inputItems, null, 2)}</pre> : <p className="config-description">Run the workflow to inspect input data.</p>}
                  </div>
                </section>
              );
            })()}

            <InspectorDivider onMouseDown={(event) => startInspectorResize("input", event)} />
          <aside className="config-panel">
            <div className="config-header">
              <div><Settings2 size={17} /><span>Node configuration</span></div>
              <button className="icon-button" onClick={() => setSelectedNode(null)} title="Close configuration"><X size={17} /></button>
            </div>
            <div className="config-content">
              {(() => {
                const item = nodeCatalog.find((node) => node.type === selectedNode.data.nodeType);
                const Icon = item?.icon ?? Workflow;
                const config = selectedNode.data.config ?? {};
                return (
                  <>
                    <div className="config-node-heading">
                      <div className={`node-icon ${selectedNode.data.color} logo-${selectedNode.data.nodeType}`}>{item?.logo ? <img src={item.logo} alt="" /> : <Icon size={20} />}</div>
                      <div><h3>{selectedNode.data.label}</h3><p>{item?.group}</p></div>
                    </div>
                    <label className="field-label">Node name</label>
                    <input className="field-input" value={selectedNode.data.label} onChange={(event) => updateSelectedNode({ label: event.target.value })} />

                    {selectedNode.data.nodeType === "http" && <>
                      <div className="config-divider" /><div className="section-title">REQUEST</div>
                      <label className="field-label">Method</label>
                      <select className="field-input" value={config.method ?? "GET"} onChange={(event) => updateConfig("method", event.target.value)}>
                        {["GET", "POST", "PUT", "PATCH", "DELETE"].map((method) => <option key={method}>{method}</option>)}
                      </select>
                      <label className="field-label">URL</label>
                      <input className="field-input" placeholder="https://api.example.com/data" value={config.url ?? ""} onChange={(event) => updateConfig("url", event.target.value)} />
                      <label className="field-label">Headers (JSON)</label>
                      <JsonEditor placeholder={'{\n  "Content-Type": "application/json"\n}'} value={config.headers} onChange={(value) => updateConfig("headers", value)} />
                      <label className="field-label">Body (JSON)</label>
                      <JsonEditor placeholder={'{\n  "message": "Hello"\n}'} value={config.body} onChange={(value) => updateConfig("body", value)} />
                    </>}

                    {selectedNode.data.nodeType === "webhook" && <>
                      <div className="config-divider" /><div className="section-title">WEBHOOK</div>
                      <p className="config-description">
                        Send a POST request to trigger this workflow.
                      </p>
                      {workflowId ? (
                        <>
                          <label className="field-label">Webhook URL</label>
                          <div className="webhook-url-row">
                            <input
                              className="field-input"
                              value={`${import.meta.env.VITE_API_BASE_URL}/api/webhook/${workflowId}`}
                              readOnly
                            />
                            <button
                              className="secondary-button"
                              onClick={async () => {
                                const url = `${import.meta.env.VITE_API_BASE_URL}/api/webhook/${workflowId}`;
                                await navigator.clipboard.writeText(url);
                                setNotice("Webhook URL copied.");
                              }}
                            >
                              Copy
                            </button>
                          </div>
                        </>
                      ) : (
                        <p className="config-description">
                          Save the workflow first to generate its webhook URL.
                        </p>
                      )}
                    </>}

                    {selectedNode.data.nodeType === "if" && <>
                      <div className="config-divider" /><div className="section-title">CONDITION</div>
                      <label className="field-label">Input field</label>
                      <input className="field-input" placeholder="status" value={config.field ?? ""} onChange={(event) => updateConfig("field", event.target.value)} />
                      <label className="field-label">Operation</label>
                      <select className="field-input" value={config.operation ?? "EQUALS"} onChange={(event) => updateConfig("operation", event.target.value)}>
                        {["EQUALS", "NOT_EQUALS", "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "LESS_THAN_OR_EQUAL"].map((op) => <option key={op}>{op}</option>)}
                      </select>
                      <label className="field-label">Expected value</label>
                      <input className="field-input" value={config.value ?? ""} onChange={(event) => updateConfig("value", event.target.value)} />
                    </>}

                    {selectedNode.data.nodeType === "transform" && <>
                      <div className="config-divider" /><div className="section-title">TRANSFORM</div>
                      <label className="field-label">Operation</label>
                      <select className="field-input" value={config.operationType ?? config.operations?.[0]?.type ?? "SET_FIELD"} onChange={(event) => updateConfig("operationType", event.target.value)}>
                        {["SELECT_FIELD", "RENAME", "SET_FIELD", "REMOVE_FIELD", "UPPERCASE", "LOWERCASE", "TRIM", "EXTRACT_DOMAIN", "MAP_FIELDS"].map((type) => <option key={type}>{type}</option>)}
                      </select>
                      {(config.operationType === "SELECT_FIELD" || config.operations?.[0]?.type === "SELECT_FIELD") && <>
                        <label className="field-label">Fields</label>
                        <input className="field-input" placeholder="name, email, status" value={(config.selectedFields ?? config.operations?.[0]?.field ?? []).join(", ")} onChange={(event) => updateConfig("selectedFields", event.target.value.split(",").map((field) => field.trim()).filter(Boolean))} />
                      </>}
                      {(config.operationType === "MAP_FIELDS" || config.operations?.[0]?.type === "MAP_FIELDS") && <>
                        <label className="field-label">Mappings JSON</label>
                        <JsonEditor value={config.mappings ?? config.operations?.[0]?.mappings ?? {}} onChange={(value) => updateConfig("mappings", value)} />
                      </>}
                      {config.operationType !== "SELECT_FIELD" && config.operationType !== "MAP_FIELDS" && config.operations?.[0]?.type !== "SELECT_FIELD" && config.operations?.[0]?.type !== "MAP_FIELDS" && <>
                        <label className="field-label">Source field</label>
                        <input className="field-input" placeholder="email" value={config.sourceField ?? ""} onChange={(event) => updateConfig("sourceField", event.target.value)} />
                        {config.operationType !== "REMOVE_FIELD" && <>
                          <label className="field-label">Target field</label>
                          <input className="field-input" placeholder="normalizedEmail" value={config.targetField ?? ""} onChange={(event) => updateConfig("targetField", event.target.value)} />
                        </>}
                        {config.operationType === "SET_FIELD" && <>
                          <label className="field-label">Value</label>
                          <input className="field-input" value={config.operationValue ?? ""} onChange={(event) => updateConfig("operationValue", event.target.value)} />
                        </>}
                      </>}
                    </>}

                    {selectedNode.data.nodeType === "delay" && <>
                      <div className="config-divider" /><div className="section-title">WAIT</div>
                      <label className="field-label">Duration (seconds)</label>
                      <input className="field-input" type="number" min="0" max="300" value={config.durationMs == null ? "" : Number(config.durationMs) / 1000} onChange={(event) => updateConfig("durationMs", Number(event.target.value) * 1000)} />
                    </>}

                    {selectedNode.data.nodeType === "merge" && <>
                      <div className="config-divider" /><div className="section-title">MERGE</div>
                      <label className="field-label">Merge mode</label>
                      <select className="field-input" value={config.mode ?? "APPEND"} onChange={(event) => updateConfig("mode", event.target.value)}>
                        <option value="APPEND">Append</option>
                        <option value="COMBINE">Combine</option>
                        <option value="CHOOSE_BRANCH">Choose input</option>
                        <option value="NAMESPACE">Namespace</option>
                      </select>

                      {config.mode === "COMBINE" && <>
                        <label className="field-label">Combine by</label>
                        <select className="field-input" value={config.combineBy ?? "POSITION"} onChange={(event) => updateConfig("combineBy", event.target.value)}>
                          <option value="POSITION">Position</option>
                          <option value="MATCHING_FIELD">Matching field</option>
                        </select>
                        {config.combineBy === "MATCHING_FIELD" && <>
                          <label className="field-label">Matching fields</label>
                          <input className="field-input" placeholder="userId, email" value={Array.isArray(config.matchingField) ? config.matchingField.join(", ") : ""} onChange={(event) => updateConfig("matchingField", event.target.value.split(",").map((field) => field.trim()).filter(Boolean))} />
                        </>}
                        <label className="field-label">Conflict policy</label>
                        <select className="field-input" value={config.conflictPolicy ?? "PREFER_INPUT_1"} onChange={(event) => updateConfig("conflictPolicy", event.target.value)}>
                          <option value="PREFER_INPUT_1">Prefer input 1</option>
                          <option value="PREFER_INPUT_2">Prefer input 2</option>
                          <option value="ERROR">Fail on conflict</option>
                        </select>
                        <label className="checkbox-field">
                          <input type="checkbox" checked={config.keepUnmatched ?? true} onChange={(event) => updateConfig("keepUnmatched", event.target.checked)} />
                          Keep unmatched records
                        </label>
                      </>}

                      {config.mode === "CHOOSE_BRANCH" && <>
                        <label className="field-label">Input</label>
                        <select className="field-input" value={config.input ?? "INPUT_1"} onChange={(event) => updateConfig("input", event.target.value)}>
                          <option value="INPUT_1">Input 1</option>
                          <option value="INPUT_2">Input 2</option>
                        </select>
                      </>}

                      {config.mode === "NAMESPACE" && <>
                        <label className="field-label">Input 1 namespace</label>
                        <input className="field-input" value={config.input1_Name ?? "input1"} onChange={(event) => updateConfig("input1_Name", event.target.value)} />
                        <label className="field-label">Input 2 namespace</label>
                        <input className="field-input" value={config.input2_Name ?? "input2"} onChange={(event) => updateConfig("input2_Name", event.target.value)} />
                      </>}
                    </>}

                    {selectedNode.data.nodeType === "gmail" && <>
                      <div className="config-divider" /><div className="section-title">EMAIL</div>
                      <label className="field-label">To</label>
                      <input className="field-input" placeholder="recipient@example.com" value={config.to ?? ""} onChange={(event) => updateConfig("to", event.target.value)} />
                      <label className="field-label">Subject</label>
                      <input className="field-input" value={config.subject ?? ""} onChange={(event) => updateConfig("subject", event.target.value)} />
                      <label className="field-label">Body</label>
                      <textarea className="field-input field-textarea" value={config.body ?? config.message ?? ""} onChange={(event) => updateConfig("body", event.target.value)} />
                    </>}

                    {selectedNode.data.nodeType === "telegram" && <>
                      <div className="config-divider" /><div className="section-title">MESSAGE</div>
                      <label className="field-label">Chat ID</label>
                      <input className="field-input" value={config.chatId ?? ""} onChange={(event) => updateConfig("chatId", event.target.value)} />
                      <label className="field-label">Message</label>
                      <textarea className="field-input field-textarea" value={config.message ?? ""} onChange={(event) => updateConfig("message", event.target.value)} />
                    </>}
                    {selectedNode.data.nodeType === "manual" && <p className="config-description">Starts this workflow when you click Run workflow.</p>}
                  </>
                );
              })()}
            </div>
            <div className="config-footer">
              <button className="delete-button" onClick={deleteSelectedNode}><Trash2 size={14} /> Delete node</button>
              <button className="secondary-button" onClick={() => setSelectedNode(null)}>Close</button>
            </div>
          </aside>

          {(() => {
            const nodeResult = nodeExecutions.find((result) => String(result.nodeId) === String(selectedNode.id));
            const outputItems = nodeResult?.outputData?.items ?? [];
            return (
              <>
                <InspectorDivider onMouseDown={(event) => startInspectorResize("output", event)} />
              <section className="inspector-side-panel inspector-output-panel" style={{ width: outputPanelWidth }}>
                <div className="inspector-side-header"><span>Output</span><span className={`execution-pill ${nodeResult?.status?.toLowerCase() ?? "idle"}`}>{nodeResult?.status ?? "NOT RUN"}</span></div>
                <div className="inspector-side-content">
                  {nodeResult ? <>
                    <div className="execution-meta">Attempts: {nodeResult.attempts ?? 0}</div>
                    <pre className="data-preview">{JSON.stringify(outputItems, null, 2)}</pre>
                    {nodeResult.status === "FAILED" && <p className="execution-error">{nodeResult.output}</p>}
                  </> : <p className="config-description">Run the workflow to inspect output data.</p>}
                </div>
              </section>
              </>
            );
          })()}
          </div>
        )}
      </div>
      {notice && <div className="status-notice" role="status"><span>{notice}</span><button className="icon-button" onClick={() => setNotice("")} aria-label="Dismiss message"><X size={15} /></button></div>}
    </div>
  );
}

export default App;
