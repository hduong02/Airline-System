# Notification service email in development

Start MailHog from the repository root:

```sh
docker compose -f docker-compose/docker-compose.dev.yml up -d mailhog
```

The notification service sends booking confirmations to MailHog at `localhost:1025` by default. Open <http://localhost:8025> to inspect captured messages. MailHog does not deliver to real inboxes and requires no Gmail account or app password.

If the notification service runs in a container on the same Compose network, set `MAIL_HOST=mailhog`. The SMTP port can be changed with `MAIL_PORT`, and the sender address with `MAIL_FROM`.
