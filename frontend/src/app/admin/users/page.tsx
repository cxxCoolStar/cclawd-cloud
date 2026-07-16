"use client";

import { useEffect, useState } from "react";
import {
  adminListUsers,
  adminCreateUser,
  adminUpdateUser,
  adminDeleteUser,
  adminResetPassword,
  getRegistration,
  setRegistration,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
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
import { Users, KeyRound, Trash2, Plus } from "lucide-react";

interface UserRow {
  id: string;
  username: string;
  email: string;
  displayName?: string;
  role: string;
  status: string;
}

export default function AdminUsersPage() {
  const [users, setUsers] = useState<UserRow[]>([]);
  const [error, setError] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({
    username: "",
    email: "",
    password: "",
    displayName: "",
    role: "user",
  });

  const [deleteTarget, setDeleteTarget] = useState<UserRow | null>(null);
  const [resetTarget, setResetTarget] = useState<UserRow | null>(null);
  const [resetPwd, setResetPwd] = useState("");
  const [regOpen, setRegOpen] = useState<boolean | null>(null);

  async function refresh() {
    setError("");
    const res = await adminListUsers();
    if (res.users) setUsers(res.users);
    if (res.error) setError(res.error);
  }
  useEffect(() => {
    refresh();
    getRegistration()
      .then((r) => setRegOpen(!!r.open))
      .catch(() => setRegOpen(false));
  }, []);

  async function toggleRegistration(next: boolean) {
    // Optimistic flip; revert on error so the UI never lies about the
    // backend state.
    setRegOpen(next);
    try {
      const r = await setRegistration(next);
      setRegOpen(!!r.open);
    } catch {
      setRegOpen(!next);
      setError("Failed to update registration setting");
    }
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    const res = await adminCreateUser(form);
    if (res.error) {
      setError(res.error);
      return;
    }
    setCreateOpen(false);
    setForm({ username: "", email: "", password: "", displayName: "", role: "user" });
    refresh();
  }

  async function setRole(u: UserRow, role: string) {
    setError("");
    const res = await adminUpdateUser(u.id, { role });
    if (res.error) setError(res.error);
    refresh();
  }

  async function setStatus(u: UserRow, status: string) {
    setError("");
    const res = await adminUpdateUser(u.id, { status });
    if (res.error) setError(res.error);
    refresh();
  }

  async function handleResetPassword() {
    if (!resetTarget || !resetPwd.trim()) return;
    const res = await adminResetPassword(resetTarget.id, resetPwd);
    if (res.error) {
      setError(res.error);
      return;
    }
    setResetTarget(null);
    setResetPwd("");
  }

  async function handleDelete(u: UserRow) {
    const res = await adminDeleteUser(u.id);
    if (res.error) setError(res.error);
    setDeleteTarget(null);
    refresh();
  }

  function openCreateDialog() {
    setForm({ username: "", email: "", password: "", displayName: "", role: "user" });
    setError("");
    setCreateOpen(true);
  }

  return (
    <div className="p-6 space-y-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-semibold tracking-tight">Users</h2>
          <p className="text-sm text-muted-foreground mt-1">
            Manage platform members. Each user gets isolated agents, sessions, and keys.
          </p>
        </div>
        <Button onClick={openCreateDialog}>
          <Plus className="h-4 w-4 mr-2" />
          Add User
        </Button>
      </div>

      <Card>
        <CardContent>
          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <p className="text-sm font-medium">Open registration</p>
              <p className="text-xs text-muted-foreground">
                When on, anyone with the URL can create an account via /signup.
                When off, only you can add users from this page.
              </p>
            </div>
            <Switch
              checked={!!regOpen}
              onCheckedChange={toggleRegistration}
              disabled={regOpen === null}
              aria-label="Toggle public registration"
            />
          </div>
        </CardContent>
      </Card>

      {error && (
        <Card className="border-destructive/40 bg-destructive/5">
          <CardContent className="pt-6">
            <p className="text-sm text-destructive">{error}</p>
          </CardContent>
        </Card>
      )}

      {users.length === 0 ? (
        <div className="rounded-lg border border-border bg-card">
          <div className="flex flex-col items-center justify-center py-16">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary/10 mb-4">
              <Users className="h-7 w-7 text-primary" />
            </div>
            <p className="text-sm text-muted-foreground mb-1">No users yet</p>
            <p className="text-xs text-muted-foreground/60 mb-4">
              Add a user to give them their own scoped workspace
            </p>
            <Button variant="outline" size="sm" onClick={openCreateDialog}>
              <Plus className="h-4 w-4 mr-2" />
              Add User
            </Button>
          </div>
        </div>
      ) : (
        <div className="rounded-lg border border-border bg-card overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Username</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-medium">
                    <div>{u.username}</div>
                    {u.displayName && (
                      <div className="text-xs text-muted-foreground">{u.displayName}</div>
                    )}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">{u.email}</TableCell>
                  <TableCell>
                    <Select value={u.role} onValueChange={(v) => v && setRole(u, v)}>
                      <SelectTrigger size="sm" className="w-36">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="user">user</SelectItem>
                        <SelectItem value="super_admin">super_admin</SelectItem>
                      </SelectContent>
                    </Select>
                  </TableCell>
                  <TableCell>
                    <Select value={u.status} onValueChange={(v) => v && setStatus(u, v)}>
                      <SelectTrigger size="sm" className="w-32">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="active">active</SelectItem>
                        <SelectItem value="disabled">disabled</SelectItem>
                      </SelectContent>
                    </Select>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button
                        size="icon"
                        variant="ghost"
                        onClick={() => {
                          setResetPwd("");
                          setResetTarget(u);
                        }}
                        title="Reset password"
                      >
                        <KeyRound className="size-4" />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setDeleteTarget(u)}
                        title="Delete"
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Add User</DialogTitle>
            <DialogDescription>
              Create a new platform member. They&apos;ll get their own scoped agents, sessions, and keys.
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4 py-2">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="user-username">Username</Label>
                <Input
                  id="user-username"
                  required
                  value={form.username}
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                  placeholder="e.g. alice"
                  autoFocus
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="user-email">Email</Label>
                <Input
                  id="user-email"
                  required
                  type="email"
                  value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                  placeholder="alice@example.com"
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="user-password">Password</Label>
              <Input
                id="user-password"
                required
                type="password"
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="Initial password"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="user-display">Display name</Label>
                <Input
                  id="user-display"
                  value={form.displayName}
                  onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                  placeholder="Optional"
                />
              </div>
              <div className="space-y-1.5">
                <Label>Role</Label>
                <Select
                  value={form.role}
                  onValueChange={(v) => v && setForm({ ...form, role: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="user">user</SelectItem>
                    <SelectItem value="super_admin">super_admin</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setCreateOpen(false)}>
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={!form.username.trim() || !form.email.trim() || !form.password.trim()}
              >
                Create user
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog
        open={resetTarget !== null}
        onOpenChange={(o) => {
          if (!o) {
            setResetTarget(null);
            setResetPwd("");
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Reset password</DialogTitle>
            <DialogDescription>
              Set a new password for{" "}
              <code className="rounded bg-muted px-1.5 py-0.5 text-xs">
                {resetTarget?.username}
              </code>
              . They&apos;ll need this to log in next time.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5 py-2">
            <Label htmlFor="reset-pwd">New password</Label>
            <Input
              id="reset-pwd"
              type="password"
              value={resetPwd}
              onChange={(e) => setResetPwd(e.target.value)}
              autoFocus
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setResetTarget(null);
                setResetPwd("");
              }}
            >
              Cancel
            </Button>
            <Button onClick={handleResetPassword} disabled={!resetPwd.trim()}>
              Reset password
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete user?</AlertDialogTitle>
            <AlertDialogDescription>
              <code className="rounded bg-muted px-1.5 py-0.5 text-xs">
                {deleteTarget?.username}
              </code>{" "}
              will be removed along with all of their agents, sessions, and API keys. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => deleteTarget && handleDelete(deleteTarget)}>
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
