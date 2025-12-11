#!/usr/bin/env node

const io = require('socket.io-client');
const axios = require('axios');
const yargs = require('yargs/yargs');
const { hideBin } = require('yargs/helpers');
const chalk = require('chalk');
const Table = require('cli-table3');

// 🔥 Prometheus /metrics 관련 의존성
const client = require('prom-client');
const express = require('express');

// ===== Prometheus Registry & 메트릭 정의 =====
const register = new client.Registry();

// 기본 Node 프로세스 메트릭 (CPU, 메모리 등)
client.collectDefaultMetrics({ register });

// Gauge 메트릭들 (지금 콘솔에 찍히는 값 그대로 대응)
const gUsersCreated = new client.Gauge({
  name: 'ktb_users_created',
  help: 'Total users created (login/register) in load test',
});
const gUsersConnected = new client.Gauge({
  name: 'ktb_users_connected',
  help: 'Current connected users via Socket.IO',
});
const gUsersDisconnected = new client.Gauge({
  name: 'ktb_users_disconnected',
  help: 'Total disconnected users',
});

const gMessagesSent = new client.Gauge({
  name: 'ktb_messages_sent',
  help: 'Total messages sent so far',
});
const gMessagesReceived = new client.Gauge({
  name: 'ktb_messages_received',
  help: 'Total messages received so far',
});
const gMessagesRead = new client.Gauge({
  name: 'ktb_messages_marked_read',
  help: 'Total messages marked as read',
});
const gReadAcks = new client.Gauge({
  name: 'ktb_read_acks_received',
  help: 'Total read ack events received',
});

const gMessagesSec = new client.Gauge({
  name: 'ktb_messages_per_second',
  help: 'Messages per second (computed in printMetrics)',
});

const gLatencyAvg = new client.Gauge({
  name: 'ktb_message_latency_avg_ms',
  help: 'Average message latency in ms',
});
const gLatencyP95 = new client.Gauge({
  name: 'ktb_message_latency_p95_ms',
  help: 'P95 message latency in ms',
});
const gLatencyP99 = new client.Gauge({
  name: 'ktb_message_latency_p99_ms',
  help: 'P99 message latency in ms',
});

const gConnTimeAvg = new client.Gauge({
  name: 'ktb_connection_time_avg_ms',
  help: 'Average connection time in ms',
});

const gAuthErrors = new client.Gauge({
  name: 'ktb_auth_errors',
  help: 'Total auth errors',
});
const gConnErrors = new client.Gauge({
  name: 'ktb_connection_errors',
  help: 'Total connection errors',
});
const gMsgErrors = new client.Gauge({
  name: 'ktb_message_errors',
  help: 'Total message errors',
});

// Registry에 등록
[
  gUsersCreated,
  gUsersConnected,
  gUsersDisconnected,
  gMessagesSent,
  gMessagesReceived,
  gMessagesRead,
  gReadAcks,
  gMessagesSec,
  gLatencyAvg,
  gLatencyP95,
  gLatencyP99,
  gConnTimeAvg,
  gAuthErrors,
  gConnErrors,
  gMsgErrors,
].forEach((m) => register.registerMetric(m));

// /metrics HTTP 서버
const app = express();
const METRICS_PORT = process.env.METRICS_PORT || 9100;

app.get('/metrics', async (req, res) => {
  res.set('Content-Type', register.contentType);
  res.end(await register.metrics());
});

app.listen(METRICS_PORT, () => {
  console.log(
    `🚀 Prometheus metrics server on http://localhost:${METRICS_PORT}/metrics`,
  );
});

// ===== CLI 옵션 파싱 =====
const argv = yargs(hideBin(process.argv))
  .option('users', {
    alias: 'u',
    description: 'Total number of users to simulate',
    type: 'number',
    default: 100,
  })
  .option('rampup', {
    alias: 'r',
    description: 'Ramp-up time in seconds',
    type: 'number',
    default: 30,
  })
  .option('duration', {
    alias: 'd',
    description: 'Test duration in seconds (0 = until all messages sent)',
    type: 'number',
    default: 0,
  })
  .option('messages', {
    alias: 'm',
    description: 'Messages per user',
    type: 'number',
    default: 20,
  })
  .option('api-url', {
    description: 'Backend REST API URL',
    type: 'string',
    default: 'https://chat.goorm-ktb-017.goorm.team',
  })
  .option('socket-url', {
    description: 'Socket.IO server URL',
    type: 'string',
    default: 'wss://chat.goorm-ktb-017.goorm.team',
  })
  .option('room-id', {
    description: 'Room ID to send messages to (auto-create if not specified)',
    type: 'string',
    default: null,
  })
  .option('batch-size', {
    alias: 'b',
    description: 'Number of users to spawn simultaneously per batch',
    type: 'number',
    default: 10,
  })
  .option('batch-delay', {
    description: 'Delay between batches in milliseconds',
    type: 'number',
    default: 1000,
  })
  .help()
  .alias('help', 'h').argv;

// ===== LoadTester 클래스 =====
class LoadTester {
  constructor(config) {
    this.config = config;
    this.metrics = {
      usersCreated: 0,
      connected: 0,
      disconnected: 0,
      messagesSent: 0,
      messagesReceived: 0,
      messagesRead: 0,
      readAcksReceived: 0,
      errorsAuth: 0,
      errorsConnection: 0,
      errorsMessage: 0,
      latencies: [],
      connectionTimes: [],
      startTime: Date.now(),
    };
    this.sockets = [];
    this.metricsInterval = null;
    this.logBuffer = [];
    this.maxLogLines = 10; // Keep last 10 log lines
  }

  log(level, message, ...args) {
    const timestamp = new Date().toISOString().substring(11, 19); // HH:MM:SS only
    const formattedMessage = `[${timestamp}] ${message} ${args.join(' ')}`;

    this.logBuffer.push({ level, message: formattedMessage });
    if (this.logBuffer.length > this.maxLogLines) {
      this.logBuffer.shift();
    }
  }

  async createTestUser(userId) {
    try {
      const email = `loadtest-${userId}@test.com`;
      const password = 'Test1234!';
      const name = `LoadTest User ${userId}`;

      let authRes;
      try {
        authRes = await axios.post(
          `${this.config.apiUrl}/api/auth/login`,
          { email, password },
          { timeout: 5000 },
        );
      } catch (loginError) {
        if (
          loginError.response?.status === 401 ||
          loginError.response?.status === 404
        ) {
          this.log('info', `Registering new user: ${email}`);
          authRes = await axios.post(
            `${this.config.apiUrl}/api/auth/register`,
            { email, password, name },
            { timeout: 5000 },
          );
        } else {
          throw loginError;
        }
      }

      this.metrics.usersCreated++;
      // API 응답 구조에 따라 여기 조정 필요할 수 있음
      // 현재는 { token, sessionId, user } 직접 리턴하는 구조라고 가정
      return authRes.data;
    } catch (error) {
      this.metrics.errorsAuth++;
      this.log('error', `Failed to create/login user ${userId}:`, error.message);
      return null;
    }
  }

  async createTestRoom() {
    try {
      if (this.config.roomId) {
        this.log('info', `Using existing room: ${this.config.roomId}`);
        return this.config.roomId;
      }

      this.log('info', 'Creating test room admin user...');
      const adminAuth = await this.createTestUser('admin');
      if (!adminAuth) {
        throw new Error('Failed to create admin user for room');
      }

      const adminToken = adminAuth.token || adminAuth.data?.token;

      this.log('info', 'Creating load test room...');
      const response = await axios.post(
        `${this.config.apiUrl}/api/rooms`,
        {
          name: 'Load Test Room',
          description: 'Room for load testing - ' + new Date().toISOString(),
          participants: [],
        },
        {
          headers: {
            Authorization: `Bearer ${adminToken}`,
          },
          timeout: 10000,
        },
      );

      const roomId = response.data.data?._id || response.data._id;
      this.log('success', `Load test room created: ${roomId}`);
      return roomId;
    } catch (error) {
      this.log('error', 'Failed to create test room:', error.message);
      throw error;
    }
  }

  async simulateUser(userId, roomId) {
    const connectStartTime = Date.now();

    try {
      const authData = await this.createTestUser(userId);
      if (!authData) {
        return;
      }

      const token = authData.token || authData.data?.token;
      const sessionId = authData.sessionId || authData.data?.sessionId;
      const user = authData.user || authData.data?.user || { name: `User-${userId}` };

      if (!token || !sessionId) {
        this.metrics.errorsAuth++;
        this.log(
          'error',
          `User ${userId} missing token/sessionId: token=${token}, sessionId=${sessionId}`,
        );
        return;
      }

      const socket = io(this.config.socketUrl, {
        auth: { token, sessionId },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: 3,
        reconnectionDelay: 1000,
      });

      this.sockets.push(socket);

      return new Promise((resolve) => {
        socket.on('connect', () => {
          const connectionTime = Date.now() - connectStartTime;
          this.metrics.connected++;
          this.metrics.connectionTimes.push(connectionTime);
          this.log(
            'success',
            `User ${userId} (${user.name}) connected in ${connectionTime}ms`,
          );

          socket.emit('joinRoom', roomId);
        });

        socket.on('joinRoomSuccess', (data) => {
          this.log(
            'info',
            `User ${userId} joined room ${roomId} with ${
              data.participants?.length || 0
            } participants`,
          );

          this.sendMessages(socket, userId, roomId);
        });

        socket.on('joinRoomError', (error) => {
          this.metrics.errorsConnection++;
          this.log(
            'error',
            `User ${userId} failed to join room:`,
            error.message || JSON.stringify(error),
          );
          socket.close();
          resolve();
        });

        socket.on('message', (data) => {
          this.metrics.messagesReceived++;

          if (data._id) {
            socket.emit('markMessagesAsRead', {
              roomId: roomId,
              messageIds: [data._id],
            });
            this.metrics.messagesRead++;
          }
        });

        socket.on('messagesRead', () => {
          this.metrics.readAcksReceived++;
        });

        socket.on('error', (error) => {
          this.metrics.errorsMessage++;
          this.log('error', `User ${userId} received error:`, error);
        });

        socket.on('disconnect', (reason) => {
          this.metrics.disconnected++;
          this.log('warn', `User ${userId} disconnected:`, reason);
          resolve();
        });

        socket.on('connect_error', (error) => {
          this.metrics.errorsConnection++;
          this.log(
            'error',
            `User ${userId} connection error:`,
            error.message,
          );
          socket.close();
          resolve();
        });
      });
    } catch (error) {
      this.metrics.errorsConnection++;
      this.log('error', `User ${userId} simulation failed:`, error.message);
    }
  }

  async sendMessages(socket, userId, roomId) {
    const messageCount = this.config.messages;
    const minDelay = 1000;
    const maxDelay = 3000;

    for (let i = 0; i < messageCount; i++) {
      const delay = Math.random() * (maxDelay - minDelay) + minDelay;
      await this.sleep(delay);

      const startTime = Date.now();

      try {
        socket.emit('chatMessage', {
          room: roomId,
          type: 'text',
          content: `Load test message ${i + 1}/${messageCount} from user ${userId} at ${new Date().toISOString()}`,
        });

        this.metrics.messagesSent++;
        this.metrics.latencies.push(Date.now() - startTime);
      } catch (error) {
        this.metrics.errorsMessage++;
        this.log(
          'error',
          `User ${userId} failed to send message ${i + 1}:`,
          error.message,
        );
      }
    }

    await this.sleep(5000);
    socket.close();
  }

  sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  getPercentile(arr, percentile) {
    if (arr.length === 0) return 0;
    const sorted = [...arr].sort((a, b) => a - b);
    const index = Math.ceil((percentile / 100) * sorted.length) - 1;
    return sorted[index];
  }

  printMetrics() {
    const elapsedSeconds = (Date.now() - this.metrics.startTime) / 1000;
    const elapsed = elapsedSeconds.toFixed(1);

    const avgLatency =
      this.metrics.latencies.length > 0
        ? (
            this.metrics.latencies.reduce((a, b) => a + b, 0) /
            this.metrics.latencies.length
          ).toFixed(2)
        : 0;

    const avgConnectionTime =
      this.metrics.connectionTimes.length > 0
        ? (
            this.metrics.connectionTimes.reduce((a, b) => a + b, 0) /
            this.metrics.connectionTimes.length
          ).toFixed(2)
        : 0;

    const p95Latency =
      this.metrics.latencies.length > 0
        ? this.getPercentile(this.metrics.latencies, 95).toFixed(2)
        : 0;

    const p99Latency =
      this.metrics.latencies.length > 0
        ? this.getPercentile(this.metrics.latencies, 99).toFixed(2)
        : 0;

    const messagesPerSec =
      elapsedSeconds > 0
        ? (this.metrics.messagesSent / elapsedSeconds).toFixed(2)
        : 0;

    // 🔥 Prometheus Gauge 업데이트 (여기가 핵심)
    gUsersCreated.set(this.metrics.usersCreated);
    gUsersConnected.set(this.metrics.connected);
    gUsersDisconnected.set(this.metrics.disconnected);

    gMessagesSent.set(this.metrics.messagesSent);
    gMessagesReceived.set(this.metrics.messagesReceived);
    gMessagesRead.set(this.metrics.messagesRead);
    gReadAcks.set(this.metrics.readAcksReceived);

    gMessagesSec.set(Number(messagesPerSec));

    gLatencyAvg.set(Number(avgLatency));
    gLatencyP95.set(Number(p95Latency));
    gLatencyP99.set(Number(p99Latency));

    gConnTimeAvg.set(Number(avgConnectionTime));

    gAuthErrors.set(this.metrics.errorsAuth);
    gConnErrors.set(this.metrics.errorsConnection);
    gMsgErrors.set(this.metrics.errorsMessage);

    // ===== 콘솔용 테이블 출력 =====
    const table = new Table({
      head: [chalk.cyan('Metric'), chalk.cyan('Value')],
      colWidths: [30, 20],
    });

    table.push(
      ['Elapsed Time', `${elapsed}s`],
      ['---', '---'],
      [chalk.green('Users Created'), this.metrics.usersCreated],
      [chalk.green('Connected'), this.metrics.connected],
      [chalk.yellow('Disconnected'), this.metrics.disconnected],
      ['---', '---'],
      [chalk.green('Messages Sent'), this.metrics.messagesSent],
      [chalk.green('Messages Received'), this.metrics.messagesReceived],
      [chalk.cyan('Messages Marked Read'), this.metrics.messagesRead],
      [chalk.cyan('Read Acks Received'), this.metrics.readAcksReceived],
      ['Messages/sec', messagesPerSec],
      ['---', '---'],
      ['Avg Message Latency', `${avgLatency}ms`],
      ['P95 Message Latency', `${p95Latency}ms`],
      ['P99 Message Latency', `${p99Latency}ms`],
      ['Avg Connection Time', `${avgConnectionTime}ms`],
      ['---', '---'],
      [chalk.red('Auth Errors'), this.metrics.errorsAuth],
      [chalk.red('Connection Errors'), this.metrics.errorsConnection],
      [chalk.red('Message Errors'), this.metrics.errorsMessage],
      [
        chalk.red('Total Errors'),
        this.metrics.errorsAuth +
          this.metrics.errorsConnection +
          this.metrics.errorsMessage,
      ],
    );

    console.clear();
    console.log(
      chalk.bold.cyan('\n=== KTB Chat Load Test - Real-time Metrics ===\n'),
    );
    console.log(table.toString());
    console.log('');

    if (this.logBuffer.length > 0) {
      console.log(chalk.bold.white('Recent Activity:'));
      console.log(chalk.gray('─'.repeat(80)));
      this.logBuffer.forEach(({ level, message }) => {
        switch (level) {
          case 'info':
            console.log(chalk.blue(message));
            break;
          case 'success':
            console.log(chalk.green(message));
            break;
          case 'warn':
            console.log(chalk.yellow(message));
            break;
          case 'error':
            console.log(chalk.red(message));
            break;
          default:
            console.log(message);
        }
      });
      console.log(chalk.gray('─'.repeat(80)));
    }
  }

  async run() {
    const { totalUsers, rampUpTime, batchSize, batchDelay } = this.config;
    const totalBatches = Math.ceil(totalUsers / batchSize);

    console.log(chalk.bold.cyan('\n=== KTB Chat Load Test ===\n'));
    console.log(chalk.white('Configuration:'));
    console.log(chalk.gray(`  Users:           ${totalUsers}`));
    console.log(chalk.gray(`  Ramp-up time:    ${rampUpTime}s`));
    console.log(chalk.gray(`  Batch size:      ${batchSize} users/batch`));
    console.log(chalk.gray(`  Batch delay:     ${batchDelay}ms`));
    console.log(chalk.gray(`  Total batches:   ${totalBatches}`));
    console.log(chalk.gray(`  Messages/user:   ${this.config.messages}`));
    console.log(chalk.gray(`  API URL:         ${this.config.apiUrl}`));
    console.log(chalk.gray(`  Socket.IO URL:   ${this.config.socketUrl}`));
    console.log(
      chalk.gray(`  Room ID:         ${this.config.roomId || 'auto-create'}`),
    );
    console.log('');

    let roomId;
    try {
      roomId = await this.createTestRoom();
    } catch (error) {
      this.log('error', 'Failed to setup test room. Aborting test.');
      process.exit(1);
    }

    this.log(
      'info',
      `Starting load test: ${totalUsers} users in ${totalBatches} batches`,
    );
    this.log(
      'info',
      `Batch configuration: ${batchSize} users every ${batchDelay}ms`,
    );
    this.log('info', `Target room: ${roomId}`);

    this.printMetrics();

    this.metricsInterval = setInterval(() => this.printMetrics(), 2000);

    const promises = [];
    for (let batch = 0; batch < totalBatches; batch++) {
      const batchStart = batch * batchSize;
      const batchEnd = Math.min(batchStart + batchSize, totalUsers);
      const batchNum = batch + 1;

      this.log(
        'info',
        `Spawning batch ${batchNum}/${totalBatches} (users ${batchStart}-${
          batchEnd - 1
        })...`,
      );

      for (let i = batchStart; i < batchEnd; i++) {
        promises.push(this.simulateUser(i, roomId));
      }

      if (batch < totalBatches - 1) {
        await this.sleep(batchDelay);
      }
    }

    this.log('info', 'All users spawned, waiting for completion...');

    await Promise.all(promises);

    clearInterval(this.metricsInterval);
    this.printMetrics();

    console.log(chalk.bold.green('\n✓ Load test completed!\n'));
    // 필요하면 process.exit(0) 유지 / 제거 선택
    process.exit(0);
  }
}

// ===== 메인 실행 =====
const tester = new LoadTester({
  apiUrl: argv.apiUrl,
  socketUrl: argv.socketUrl,
  roomId: argv.roomId,
  totalUsers: argv.users,
  rampUpTime: argv.rampup,
  duration: argv.duration,
  messages: argv.messages,
  batchSize: argv.batchSize,
  batchDelay: argv.batchDelay,
});

tester.run().catch((error) => {
  console.error(chalk.red('Fatal error:'), error);
  process.exit(1);
});
