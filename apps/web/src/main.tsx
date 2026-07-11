import React from 'react';
import ReactDOM from 'react-dom/client';
import {
  Archive,
  Bell,
  BookLock,
  BookOpen,
  Check,
  ChevronDown,
  Clock,
  Cloud,
  Download,
  Edit3,
  FileText,
  Folder,
  Hash,
  Home,
  Import,
  KeyRound,
  Link2,
  Lock,
  MoreHorizontal,
  PanelRight,
  Plus,
  RefreshCw,
  Search,
  Settings,
  Shield,
  Star,
  Tag,
  Trash2,
  Upload,
  UserRound,
  type LucideIcon,
} from 'lucide-react';
import { marked } from 'marked';
import './styles.css';

type NavItem = {
  label: string;
  Icon: LucideIcon;
  active?: boolean;
  count?: number;
};

type NoteCard = {
  title: string;
  preview: string;
  notebook: string;
  time: string;
  words: number;
  tags: string[];
  starred?: boolean;
  locked?: boolean;
  active?: boolean;
};

const notes: NoteCard[] = [
  {
    title: 'The Meaning of Sync',
    preview: 'A Drive-backed sync chain should feel invisible until a conflict needs review.',
    notebook: 'Product / Sync',
    time: '4h ago',
    words: 274,
    tags: ['sync', 'drive'],
    starred: true,
    active: true,
  },
  {
    title: 'Conflict handling policy',
    preview: 'Admins decide team conflicts. Personal workspaces can keep both automatically.',
    notebook: 'Product / Security',
    time: 'Yesterday',
    words: 481,
    tags: ['conflicts'],
    locked: true,
  },
  {
    title: 'Markdown import checklist',
    preview: 'Import files, folders, ZIP archives, and preview raw Markdown like a PDF viewer.',
    notebook: 'Imports',
    time: '2d ago',
    words: 166,
    tags: ['markdown', 'import'],
  },
  {
    title: 'Workspace profile fields',
    preview: 'Cover image crop, profile picture crop, username, public name, device name.',
    notebook: 'Identity',
    time: '7d ago',
    words: 309,
    tags: ['profile'],
  },
];

const sidebar: NavItem[] = [
  { label: 'Notes', Icon: Home, active: true, count: 18 },
  { label: 'Favorites', Icon: Star, count: 3 },
  { label: 'Reminders', Icon: Bell, count: 5 },
  { label: 'Monographs', Icon: Upload },
  { label: 'Vault', Icon: BookLock, count: 2 },
  { label: 'Archive', Icon: Archive },
  { label: 'Trash', Icon: Trash2 },
];

const notebooks = [
  ['Product', '6'],
  ['Sync', '4'],
  ['Security', '3'],
  ['Imports', '2'],
];

const tags = ['sync', 'drive', 'conflicts', 'markdown', 'profile'];

const editorMarkdown = `# The Meaning of Sync

A Drive-backed sync chain should feel like Notesnook-style sync: visible status, simple organization, and a focused editor.

## Norfold decisions

- Create sync chain is the primary setup action.
- Restore is always visible beside create.
- Google Drive is first, OneDrive follows.
- App password, OAuth tokens, and device private keys never sync.
- Admins resolve team conflicts with red/green diffs.

> The UI should stay boring in the best way: sidebar, notes list, editor, and clear privacy status.

[[Conflict handling policy]]
`;

function App() {
  return (
    <main className="app-shell">
      <aside className="left-rail">
        <div className="brand-row">
          <img src="/icon.jpg" alt="" />
          <div>
            <strong>Norfold</strong>
            <span>Privacy for everyone</span>
          </div>
        </div>

        <button className="new-note"><Plus size={18} /> New note</button>

        <label className="search-box">
          <Search size={17} />
          <input placeholder="Search notes" />
        </label>

        <nav className="nav-group">
          {sidebar.map(({ label, Icon, active, count }) => (
            <button className={active ? 'nav-item active' : 'nav-item'} key={label}>
              <Icon size={18} />
              <span>{label}</span>
              {count ? <small>{count}</small> : null}
            </button>
          ))}
        </nav>

        <div className="sidebar-section">
          <div className="section-label">
            <span>Notebooks</span>
            <Plus size={15} />
          </div>
          {notebooks.map(([name, count]) => (
            <button className="tree-item" key={name}>
              <Folder size={16} />
              <span>{name}</span>
              <small>{count}</small>
            </button>
          ))}
        </div>

        <div className="sidebar-section">
          <div className="section-label">
            <span>Tags</span>
            <Plus size={15} />
          </div>
          {tags.map((tag) => (
            <button className="tree-item" key={tag}>
              <Hash size={16} />
              <span>{tag}</span>
            </button>
          ))}
        </div>

        <div className="sync-card">
          <div>
            <Cloud size={17} />
            <strong>Google Drive</strong>
          </div>
          <span>Synced just now</span>
          <button><RefreshCw size={15} /> Sync now</button>
        </div>

        <div className="rail-footer">
          <button><Import size={17} /> Import</button>
          <button><Settings size={17} /> Settings</button>
        </div>
      </aside>

      <section className="notes-pane">
        <header className="pane-header">
          <div>
            <strong>Notes</strong>
            <span>18 notes</span>
          </div>
          <button><ChevronDown size={17} /> Updated</button>
        </header>

        <div className="setup-strip">
          <Shield size={18} />
          <div>
            <strong>Create sync chain</strong>
            <span>Restore or local-only remains available in setup.</span>
          </div>
          <button>Create</button>
        </div>

        <div className="note-list">
          {notes.map((note) => (
            <article className={note.active ? 'note-card active' : 'note-card'} key={note.title}>
              <div className="note-title-row">
                <strong>{note.title}</strong>
                <span>{note.starred ? <Star size={15} /> : note.locked ? <Lock size={15} /> : null}</span>
              </div>
              <p>{note.preview}</p>
              <footer>
                <span>{note.notebook}</span>
                <span>{note.time}</span>
              </footer>
              <div className="tag-row">
                {note.tags.map((tag) => <i key={tag}>#{tag}</i>)}
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="editor-pane">
        <header className="editor-toolbar">
          <div className="title-cluster">
            <h1>The Meaning of Sync</h1>
            <div className="note-meta">
              <span><Clock size={14} /> Mon, Jul 06, 2026, 2:58 PM</span>
              <span><Check size={14} /> Saved</span>
              <span>274 words</span>
            </div>
          </div>
          <div className="toolbar-actions">
            <button><Star size={17} /></button>
            <button><Lock size={17} /></button>
            <button><MoreHorizontal size={18} /></button>
          </div>
        </header>

        <div className="editor-tabs">
          <button className="selected"><Edit3 size={16} /> Editor</button>
          <button><BookOpen size={16} /> Preview</button>
          <button><Link2 size={16} /> Backlinks</button>
        </div>

        <div className="tag-editor">
          <Tag size={16} />
          <span>#sync</span>
          <span>#drive</span>
          <button>Add a tag...</button>
        </div>

        <article className="document" dangerouslySetInnerHTML={{ __html: marked.parse(editorMarkdown) }} />

        <footer className="statusbar">
          <span>16px</span>
          <span>Paragraph</span>
          <span>Sans-serif</span>
          <span>Encrypted locally before Drive upload</span>
        </footer>
      </section>

      <aside className="right-panel">
        <header>
          <strong>Properties</strong>
          <PanelRight size={18} />
        </header>

        <div className="property-card profile">
          <div className="avatar"><UserRound size={28} /></div>
          <strong>Sheikh</strong>
          <span>@sheikh · Admin</span>
        </div>

        <div className="property-card">
          <strong>Sync chain</strong>
          <p><Cloud size={15} /> Google Drive</p>
          <p><KeyRound size={15} /> Admin policy signed</p>
          <p><Shield size={15} /> Private secrets stay local</p>
        </div>

        <div className="property-card">
          <strong>Notebook</strong>
          <p><Folder size={15} /> Product / Sync</p>
          <p><FileText size={15} /> Linked in 2 notebooks</p>
        </div>

        <div className="property-card conflict">
          <strong>Conflict review</strong>
          <p className="removed">- Resolve conflicts silently</p>
          <p className="added">+ Admin reviews red/green diff</p>
          <button>Open conflict center</button>
        </div>

        <div className="property-card">
          <strong>Setup</strong>
          <button><Upload size={16} /> Create sync chain</button>
          <button><Download size={16} /> Restore chain</button>
          <button><Folder size={16} /> Local only</button>
        </div>
      </aside>
    </main>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(<App />);
