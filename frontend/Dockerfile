FROM node:20-alpine

WORKDIR /app

COPY . .

EXPOSE 5173

CMD ["sh", "-c", "npm install && npm run dev -- --host"]
