"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { BookOpen, FileText, Files, Loader2, Trash2, Upload } from "lucide-react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { useAgentIdFromURL } from "@/hooks/use-agent-id";
import { useAgentName } from "@/hooks/use-agent-name";

const MAX_BYTES = 256 * 1024;

type KnowledgeFile = {
  name: string;
  storedName?: string;
  path: string;
  size: number;
  hash?: string;
};

export default function AgentKnowledgePage() {
  const agentId = useAgentIdFromURL();
  const agentName = useAgentName(agentId);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [files, setFiles] = useState<KnowledgeFile[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState("");
  const [dragOver, setDragOver] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeFile | null>(null);

  const fetchFiles = useCallback(async () => {
    setLoading(true);
    try {
      const res = await apiFetch(`/api/agents/${agentId}/knowledge-files`);
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data?.error || `Failed to load files (${res.status})`);
      setFiles((data.files || []) as KnowledgeFile[]);
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => {
    fetchFiles().catch(() => setLoading(false));
  }, [fetchFiles]);

  const handleUploadOpenChange = (open: boolean) => {
    setUploadOpen(open);
    if (!open) {
      setUploadFile(null);
      setUploadError("");
      setDragOver(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  const acceptFile = (list: FileList | null) => {
    if (!list?.length) return;
    if (list.length > 1) {
      setUploadError("Please upload one knowledge file at a time.");
      return;
    }
    const file = list[0];
    if (file.size > MAX_BYTES) {
      setUploadError("Knowledge file is too large; maximum size is 256KB.");
      return;
    }
    setUploadFile(file);
    setUploadError("");
  };

  const upload = async () => {
    if (!uploadFile) return;
    setUploading(true);
    setUploadError("");
    try {
      const form = new FormData();
      form.append("file", uploadFile);
      const res = await apiFetch(`/api/agents/${agentId}/knowledge-files`, {
        method: "POST",
        body: form,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || data?.ok === false) {
        throw new Error(data?.error || `Upload failed (${res.status})`);
      }
      if (data?.duplicate) {
        setUploadError("This file is already in the knowledge base.");
        await fetchFiles();
        return;
      }
      handleUploadOpenChange(false);
      await fetchFiles();
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : String(err));
    } finally {
      setUploading(false);
    }
  };

  const deleteFile = async () => {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleteTarget(null);
    const res = await apiFetch(
      `/api/agents/${agentId}/knowledge-files/${encodeURIComponent(target.storedName || target.name)}`,
      { method: "DELETE" },
    );
    if (res.ok) fetchFiles();
  };

  return (
    <div className="p-6 space-y-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-semibold tracking-tight">Knowledge</h2>
          <p className="text-sm text-muted-foreground mt-1">
            Reference files scoped to <strong>{agentName}</strong>
          </p>
        </div>
        <Button variant="outline" onClick={() => setUploadOpen(true)}>
          <Upload className="h-4 w-4 mr-2" />
          Upload File
        </Button>
      </div>

      {loading ? (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-32" />
          ))}
        </div>
      ) : files.length === 0 ? (
        <div className="rounded-lg border border-border bg-card">
          <div className="flex flex-col items-center justify-center py-16">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10 mb-4">
              <BookOpen className="h-7 w-7 text-primary" />
            </div>
            <p className="text-sm text-muted-foreground mb-1">No knowledge files yet</p>
            <p className="text-xs text-muted-foreground/60 mb-4 max-w-sm text-center">
              Upload reference files this agent should use when answering.
            </p>
            <Button variant="outline" size="sm" onClick={() => setUploadOpen(true)}>
              <Upload className="h-4 w-4 mr-2" />
              Upload File
            </Button>
          </div>
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {files.map((file) => (
            <div
              key={file.path}
              className="group rounded-lg border border-border bg-card p-5 transition-colors hover:bg-muted/50"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="flex min-w-0 items-center gap-2.5">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                    <FileText className="h-4 w-4 text-primary" />
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{file.name}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {formatBytes(file.size)}
                      {file.hash ? ` · ${file.hash.slice(0, 8)}` : ""}
                    </p>
                  </div>
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-7 w-7 shrink-0 text-muted-foreground opacity-0 transition-opacity hover:text-destructive group-hover:opacity-100"
                  onClick={() => setDeleteTarget(file)}
                  title="Delete knowledge file"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Dialog open={uploadOpen} onOpenChange={handleUploadOpenChange}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Upload knowledge file</DialogTitle>
          </DialogHeader>
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            accept=".md,.markdown,.txt,.csv,.json,.yaml,.yml,.log"
            onChange={(event) => acceptFile(event.target.files)}
          />
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(event) => {
              event.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(event) => {
              event.preventDefault();
              setDragOver(false);
              acceptFile(event.dataTransfer.files);
            }}
            className={`flex h-48 w-full flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed bg-muted/20 px-6 py-8 text-center transition-colors hover:bg-muted/40 ${
              dragOver ? "border-primary bg-primary/5" : "border-border"
            }`}
          >
            <Files
              className={`h-10 w-10 ${
                uploadFile ? "text-primary" : "text-muted-foreground/60"
              }`}
              strokeWidth={1.4}
            />
            {uploadFile ? (
              <div className="space-y-1">
                <p className="break-all text-sm font-medium">{uploadFile.name}</p>
                <p className="text-xs text-muted-foreground">
                  {formatBytes(uploadFile.size)} · click to choose a different file
                </p>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                Drag and drop or click to upload
              </p>
            )}
          </button>
          <p className="text-xs text-muted-foreground">
            Supported text files up to 256KB. Existing files with the same name are replaced.
          </p>
          {uploadError && (
            <p className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive break-words">
              {uploadError}
            </p>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => handleUploadOpenChange(false)} disabled={uploading}>
              Cancel
            </Button>
            <Button onClick={upload} disabled={!uploadFile || uploading}>
              {uploading ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Uploading...
                </>
              ) : (
                <>
                  <Upload className="h-4 w-4 mr-2" />
                  Upload
                </>
              )}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete knowledge file?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes {deleteTarget?.name} from this agent knowledge base.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={deleteFile} className="bg-destructive text-destructive-foreground hover:bg-destructive/90">
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
