
FROM node:22-alpine

WORKDIR /app

COPY package*.json ./

RUN npm install --omit=dev

COPY server.mjs ./

EXPOSE 8787

ENV HOST=0.0.0.0
ENV PORT=8787
ENV NODE_ENV=production

CMD ["npm", "start"]
