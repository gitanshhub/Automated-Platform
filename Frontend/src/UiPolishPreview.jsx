import {
  Activity, ArrowRight, Bell, Bot, Check, ChevronDown, ChevronRight,
  CircleHelp, Clock3, Cloud, Command, Edit3, GitBranch, Globe, KeyRound,
  LayoutDashboard, LayoutTemplate, Link2, Mail, Map, MessageCircle,
  MoreHorizontal, Play, Plus, Search, Settings, SlidersHorizontal, Sparkles,
  Table2, Variable, Webhook, Workflow
} from "lucide-react";
import "./ui-polish-preview.css";

const navigation = [
  { name: "Overview", icon: LayoutDashboard }, { name: "Workflows", icon: Workflow, active: true },
  { name: "Executions", icon: Activity }, { name: "Templates", icon: LayoutTemplate },
  { name: "Connections", icon: Link2 }, { name: "Credentials", icon: KeyRound },
  { name: "Variables", icon: Variable }, { name: "Settings", icon: Settings }
];

const categories = [
  { name: "Triggers", count: 2, open: true, nodes: [{ name: "Manual Trigger", description: "Start a workflow on demand", icon: Play, tone: "amber" }, { name: "Webhook", description: "Start from an incoming request", icon: Webhook, tone: "amber" }] },
  { name: "Actions", count: 2, open: true, nodes: [{ name: "HTTP Request", description: "Call any REST API", icon: Globe, tone: "blue" }, { name: "Delay", description: "Pause before the next step", icon: Clock3, tone: "blue" }] },
  { name: "AI & ML", count: 2, open: false, icon: Bot },
  { name: "Data & Transform", count: 2, open: false, icon: Table2 },
  { name: "Integrations", count: 2, open: false, nodes: [{ name: "Gmail", description: "Send an email", icon: Mail, tone: "teal" }, { name: "Telegram", description: "Send a message", icon: MessageCircle, tone: "teal" }] },
  { name: "Logic", count: 2, open: false, nodes: [{ name: "IF Condition", description: "Route data by a condition", icon: GitBranch, tone: "violet" }, { name: "Merge", description: "Combine two branches", icon: GitBranch, tone: "violet" }] }
];

const templates = [
  { title: "AI → Telegram", description: "Ask a model and send the answer", icon: Bot, tone: "violet" },
  { title: "Webhook → Gmail", description: "Receive data and email a report", icon: Mail, tone: "teal" },
  { title: "Manual → HTTP", description: "Run an API request on demand", icon: Globe, tone: "blue" }
];

export default function UiPolishPreview() {
  return <div className="polish-preview">
    <header className="preview-topbar">
      <div className="preview-brand"><span className="preview-brand-mark"><Workflow size={16} /></span><strong>Flowforge</strong></div>
      <div className="preview-titlebar"><span className="preview-titlebar-muted">Workflows</span><ChevronRight size={13} /><strong>User Onboarding Automation</strong><button className="preview-edit-title" aria-label="Edit workflow title"><Edit3 size={13} /></button><span className="preview-pill saved"><Check size={12} /> Saved</span></div>
      <div className="preview-top-actions"><button className="preview-top-icon" aria-label="Help"><CircleHelp size={16} /></button><button className="preview-top-icon" aria-label="Notifications"><Bell size={16} /><i /></button><span className="preview-avatar">AS</span><button className="preview-run"><Play size={13} /> Run <ChevronDown size={13} /></button></div>
    </header>
    <div className="preview-workspace">
      <aside className="preview-sidebar">
        <div className="preview-sidebar-section"><div className="preview-sidebar-label">Workspace</div><nav className="preview-primary-nav">{navigation.map(({ name, icon: Icon, active }) => <button className={`preview-nav-item${active ? " active" : ""}`} key={name}><Icon size={15} /><span>{name}</span>{name === "Workflows" && <span className="preview-nav-count">12</span>}</button>)}</nav></div>
        <div className="preview-library-heading"><span>Node library</span><button className="preview-icon-button" aria-label="Help"><CircleHelp size={15} /></button></div>
        <div className="preview-search"><Search size={14} /><input placeholder="Search nodes" /><kbd><Command size={10} /> K</kbd></div>
        <div className="preview-recent-label">RECENTLY USED</div><button className="preview-recent"><span className="preview-mini-icon violet"><Bot size={13} /></span><span>OpenRouter AI</span><Plus size={14} /></button>
        <div className="preview-category-list">{categories.map((category) => <section className="preview-category" key={category.name}><button className="preview-category-heading"><span className="preview-category-title">{category.open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}{category.name}</span><span className="preview-category-count">{category.count}</span></button>{category.open && category.nodes?.map((node) => { const Icon = node.icon; return <button className="preview-library-node" key={node.name}><span className={`preview-mini-icon ${node.tone}`}><Icon size={13} /></span><span className="preview-library-copy"><strong>{node.name}</strong><small>{node.description}</small></span><Plus size={14} className="preview-add" /></button>; })}</section>)}</div>
        <button className="preview-browse"><SlidersHorizontal size={14} /> Browse all nodes <ArrowRight size={13} /></button><div className="preview-sidebar-footer"><button className="preview-help-link"><CircleHelp size={14} /><span>Help & shortcuts</span><kbd>?</kbd></button></div>
      </aside>
      <main className="preview-canvas-area">
        <div className="preview-canvas-toolbar"><div className="preview-canvas-heading"><strong>Canvas</strong><span>Build your automation visually</span></div><div className="preview-toolbar-actions"><button className="preview-tool-button"><Search size={14} /> Find</button><button className="preview-tool-button"><Map size={14} /> Fit view</button><button className="preview-tool-button"><Plus size={14} /> Add node</button><button className="preview-more" aria-label="More actions"><MoreHorizontal size={17} /></button></div></div>
        <div className="preview-canvas"><div className="preview-empty-state"><div className="preview-graph-illustration" aria-hidden="true"><span className="preview-graph-node node-a"><Play size={14} /></span><span className="preview-graph-node node-b"><Sparkles size={14} /></span><span className="preview-graph-node node-c"><ArrowRight size={14} /></span><span className="preview-graph-edge edge-a" /><span className="preview-graph-edge edge-b" /></div><span className="preview-eyebrow">NEW WORKFLOW</span><h1>Start with an empty canvas</h1><p>Build powerful workflows by connecting triggers, actions, and logic.</p><div className="preview-template-grid">{templates.map((template) => { const Icon = template.icon; return <button className="preview-template" key={template.title}><span className={`preview-template-icon ${template.tone}`}><Icon size={16} /></span><span><strong>{template.title}</strong><small>{template.description}</small></span><Check size={15} className="preview-template-check" /></button>; })}</div><button className="preview-primary"><Plus size={14} /> Browse nodes</button></div><div className="preview-minimap"><div className="preview-map-label">MINIMAP</div><div className="preview-map-node one" /><div className="preview-map-node two" /><div className="preview-map-line" /><div className="preview-zoom"><button>−</button><span>100%</span><button>+</button></div></div></div>
        <footer className="preview-statusbar"><span><strong>0</strong> nodes</span><span><strong>0</strong> connections</span><span className="preview-status-spacer" /><span><Cloud size={12} /> All changes saved</span><span className="preview-online"><i /> Backend connected</span></footer>
      </main>
    </div>
  </div>;
}
