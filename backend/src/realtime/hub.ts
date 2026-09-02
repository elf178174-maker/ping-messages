import type { WebSocket } from 'ws';


interface Connection {
  socket: WebSocket;
  userId: string;
  deviceId: string;
  /** Conversations this device asked to receive live events for. */
  subscriptions: Set<string>;
}

/**
 * The in-memory fan-out registry.
 *
 * One process holds its own connections. **This does not scale past a single
 * instance as written** — two API replicas would each only reach their own
 * clients. The fix is a Redis (or Postgres LISTEN/NOTIFY) pub/sub behind the
 * same `publish` interface; `docs/BACKEND.md` describes exactly that, and the
 * shape here is deliberately narrow so swapping the transport touches one file.
 */
class RealtimeHub {
  private readonly byUser = new Map<string, Set<Connection>>();
  private readonly byDevice = new Map<string, Connection>();

  add(socket: WebSocket, userId: string, deviceId: string): Connection {
    // A reconnect from the same device replaces the old socket rather than
    // accumulating one, which is what stops a flaky network from leaving a
    // user with a dozen half-dead connections all receiving duplicates.
    this.removeDevice(deviceId);

    const connection: Connection = { socket, userId, deviceId, subscriptions: new Set() };
    const set = this.byUser.get(userId) ?? new Set();
    set.add(connection);
    this.byUser.set(userId, set);
    this.byDevice.set(deviceId, connection);
    return connection;
  }

  remove(connection: Connection): void {
    const set = this.byUser.get(connection.userId);
    set?.delete(connection);
    if (set && set.size === 0) this.byUser.delete(connection.userId);
    this.byDevice.delete(connection.deviceId);
  }

  private removeDevice(deviceId: string): void {
    const existing = this.byDevice.get(deviceId);
    if (!existing) return;
    try {
      existing.socket.close(1000, 'replaced by a newer connection');
    } catch {
      // Already gone; nothing to do.
    }
    this.remove(existing);
  }

  isOnline(userId: string): boolean {
    return (this.byUser.get(userId)?.size ?? 0) > 0;
  }

  /** Sends to every device of [userId]. */
  toUser(userId: string, payload: unknown, exceptDeviceId?: string): number {
    const connections = this.byUser.get(userId);
    if (!connections) return 0;

    const frame = JSON.stringify(payload);
    let delivered = 0;
    for (const connection of connections) {
      if (connection.deviceId === exceptDeviceId) continue;
      if (this.send(connection, frame)) delivered += 1;
    }
    return delivered;
  }

  /** Sends to every device of every listed user. */
  toUsers(userIds: Iterable<string>, payload: unknown, exceptDeviceId?: string): number {
    const frame = JSON.stringify(payload);
    let delivered = 0;
    for (const userId of userIds) {
      for (const connection of this.byUser.get(userId) ?? []) {
        if (connection.deviceId === exceptDeviceId) continue;
        if (this.send(connection, frame)) delivered += 1;
      }
    }
    return delivered;
  }

  toDevice(deviceId: string, payload: unknown): boolean {
    const connection = this.byDevice.get(deviceId);
    if (!connection) return false;
    return this.send(connection, JSON.stringify(payload));
  }

  private send(connection: Connection, frame: string): boolean {
    // readyState 1 is OPEN. Writing to a closing socket throws, and a throw
    // here would abort the rest of a fan-out, so it is caught per connection.
    if (connection.socket.readyState !== 1) return false;
    try {
      connection.socket.send(frame);
      return true;
    } catch {
      return false;
    }
  }

  get connectionCount(): number {
    return this.byDevice.size;
  }
}

export const hub = new RealtimeHub();
export type { Connection };
