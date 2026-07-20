# Deploying to EC2 (jar + systemd)

No git repo, no Docker — build locally, `scp` the jar over, run it under systemd so it survives
crashes and reboots. To update later: rebuild, `scp` the new jar over, `systemctl restart`.

## 0. Before you start

- **Resolve the shared Discord bot token conflict first.** If any other process (another app, an
  old deployment, a Claude Code session with `--channels`) is using the same bot token, make sure
  it's fully stopped before starting this one — Discord only allows one gateway connection per
  token at a time.
- Know your EC2 instance's OS (Amazon Linux 2023 vs Ubuntu — commands below cover both) and have
  SSH access sorted.

## 1. Install Java 25 on the EC2 box

This app is built for Java 25 specifically (not just "21+") — the jar won't run on an older JVM.

**Amazon Linux 2023:**
```bash
sudo dnf install -y java-25-amazon-corretto-devel
```

**Ubuntu 24.04+:**
```bash
sudo apt update && sudo apt install -y openjdk-25-jdk
```

If your distro's package repos don't have Java 25 yet, install Corretto 25 directly from
https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/generic-linux-install.html — same
approach used on this dev machine (a downloaded/homebrew build), since OS package repos lag behind
new JDK releases.

Verify: `java -version` should show 25.

## 2. Build the jar locally

```bash
cd /Users/dariorobinson/Desktop/Personal/sports-betting-app
JAVA_HOME=/usr/local/opt/openjdk ./mvnw -q -DskipTests clean package
ls -la target/kalshi-sports-betting-0.1.0.jar
```

## 3. Create the app user and directories on EC2

```bash
ssh your-ec2-host
sudo useradd --system --no-create-home --shell /usr/sbin/nologin kalshi
sudo mkdir -p /opt/kalshi-sports-betting /etc/kalshi-sports-betting
sudo chown kalshi:kalshi /opt/kalshi-sports-betting /etc/kalshi-sports-betting
```

## 4. Copy the jar, private key, and config over

From your local machine:
```bash
scp target/kalshi-sports-betting-0.1.0.jar your-ec2-host:/tmp/app.jar
scp /path/to/your/pkcs8_key.pem your-ec2-host:/tmp/kalshi_private_key.pem
scp deploy/kalshi.env.example your-ec2-host:/tmp/kalshi.env
scp deploy/kalshi-sports-betting.service your-ec2-host:/tmp/kalshi-sports-betting.service
```

On EC2:
```bash
sudo mv /tmp/app.jar /opt/kalshi-sports-betting/app.jar
sudo mv /tmp/kalshi_private_key.pem /etc/kalshi-sports-betting/kalshi_private_key.pem
sudo mv /tmp/kalshi.env /etc/kalshi-sports-betting/kalshi.env
sudo mv /tmp/kalshi-sports-betting.service /etc/systemd/system/kalshi-sports-betting.service

# Edit with real values (KALSHI_API_KEY_ID, APP_API_KEY at minimum):
sudo nano /etc/kalshi-sports-betting/kalshi.env

sudo chown kalshi:kalshi /opt/kalshi-sports-betting/app.jar \
    /etc/kalshi-sports-betting/kalshi_private_key.pem \
    /etc/kalshi-sports-betting/kalshi.env
sudo chmod 600 /etc/kalshi-sports-betting/kalshi_private_key.pem /etc/kalshi-sports-betting/kalshi.env
sudo chmod 644 /opt/kalshi-sports-betting/app.jar
```

## 5. Start it

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now kalshi-sports-betting
sudo systemctl status kalshi-sports-betting
```

## 6. Verify

```bash
# Run ON the EC2 box (it's loopback-only, won't answer from outside):
curl http://localhost:8080/api/status
curl http://localhost:8080/api/sports | head -c 300

# Watch logs:
sudo journalctl -u kalshi-sports-betting -f
```

`/api/status` should show `"kalshiCredentialsConfigured": true`. If it says `false`, check
`kalshi.env` — most likely `KALSHI_PRIVATE_KEY_PATH` doesn't match where you put the key, or
`KALSHI_API_KEY_ID` is still the placeholder.

## 7. Discord bot

Nothing further to wire up — if `DISCORD_BOT_TOKEN` (and `DISCORD_AUTHORIZED_USER_ID`,
`ANTHROPIC_API_KEY`) are set in `kalshi.env`, the bot starts automatically in this same process.
Check the log for either:
```
sudo journalctl -u kalshi-sports-betting | grep -i discord
```
A clean startup shows no warning; a missing token logs `DISCORD_BOT_TOKEN not set — the Discord
bot will not start` (harmless — the REST API still works). Once it's up, DM the bot from the
Discord account matching `DISCORD_AUTHORIZED_USER_ID` — messages from anyone else are silently
ignored.

## Updating later

```bash
# Locally:
JAVA_HOME=/usr/local/opt/openjdk ./mvnw -q -DskipTests clean package
scp target/kalshi-sports-betting-0.1.0.jar your-ec2-host:/tmp/app.jar

# On EC2:
sudo mv /tmp/app.jar /opt/kalshi-sports-betting/app.jar
sudo chown kalshi:kalshi /opt/kalshi-sports-betting/app.jar
sudo systemctl restart kalshi-sports-betting
```
