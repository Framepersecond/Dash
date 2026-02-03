# Dash

**Modern web-based admin dashboard for Minecraft servers**

Dash provides a comprehensive web interface for server administration, enabling you to manage your Minecraft server from any device with a browser. Monitor server performance, manage players, execute commands, configure settings, and handle backups—all through an elegant, modern web UI.

## Features

### 📊 Real-Time Monitoring
- Live server statistics (TPS, memory, CPU usage)
- Historical performance graphs and analytics
- Online player tracking with detailed profiles
- Console log streaming with color-coded output

### 👥 Player Management
- View all players with session history and playtime
- Kick, ban, freeze, or teleport players
- Add and manage admin notes for players
- View and edit player inventories and ender chests
- Whitelist management

### ⚙️ Server Configuration
- Modify server settings (MOTD, view distance, simulation distance)
- Configure game rules for all worlds
- Upload and manage server icon
- Enable/disable plugins dynamically

### 💾 Backup System
- Create manual backups instantly
- Schedule automatic backups
- Download backup archives
- Manage backup retention (configurable max backups)

### 📁 File Management
- Browse server files and directories
- Edit configuration files directly in the browser
- Upload files and plugins
- Upload and manage datapacks

### 🎮 Advanced Features
- Execute console commands remotely
- Teleport players to coordinates or other players
- Broadcast messages as admin or server
- Player freeze system for moderation
- Web-based registration system with time-limited codes

## Installation

1. Download the latest `Dash.jar` from the [releases page](../../releases)
2. Place the JAR file in your server's `plugins` folder
3. Restart your server
4. Configure the web port in `plugins/Dash/config.yml` (default: 8080)
5. Restart the server again to apply configuration

## Usage

### Initial Setup

1. Join your server as an operator
2. Run `/dash register` to generate a registration code
3. Open the web dashboard at `http://localhost:PORT` (replace PORT with your configured port)
4. Enter your registration code and create admin credentials
5. Log in with your new credentials

### Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/dash register` | Generate a web registration code (expires in 5 minutes) | `dash.register` |

### Permissions

| Permission | Description | Default |
|-----------|-------------|---------|
| `dash.register` | Allows generating web registration codes | op |

## Configuration

Edit `plugins/Dash/config.yml`:

```yaml
# Web server port for the admin dashboard
port: 8080

# Database settings for player data tracking
database:
  type: sqlite
  file: dash.db

# Backup settings
backups:
  directory: backups
  max-backups: 10
```

**Configuration Options:**
- `port`: Web server port (default: 8080)
- `database.type`: Database type (currently supports SQLite)
- `database.file`: Database file name
- `backups.directory`: Backup storage directory
- `backups.max-backups`: Maximum number of backups to retain

## Supported Versions

- **Minecraft:** 1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4+
- **Server Software:** Paper, Spigot, and compatible forks
- **Java:** 21 or higher

## Security

- Registration codes expire after 5 minutes
- Session-based authentication with secure cookies
- All actions are logged with IP tracking
- File upload validation and path sanitization
- Admin credentials are hashed and stored securely

## License

This project is licensed under the BSD 3-Clause License - see the LICENSE file for details.

## Author

Developed by **Frxme**

---

*For issues, feature requests, or contributions, please visit the [GitHub repository](../../issues)*
