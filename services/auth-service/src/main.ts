import type { Pool } from 'pg';
import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { AppModule, ObserveInstrument } from './app.module.js';
import { HttpExceptionFilter } from './common/http-exception.filter.js';
import { PG_POOL } from './database/pg-pool.provider.js';
import { runMigrations } from './database/run-migrations.js';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, {
    instrument: ObserveInstrument,
  });
  await runMigrations(app.get<Pool>(PG_POOL));
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      forbidNonWhitelisted: true,
      transform: true,
      errorHttpStatusCode: 422,
    }),
  );
  app.useGlobalFilters(new HttpExceptionFilter());

  const config = new DocumentBuilder()
    .setTitle('Auth service')
    .setDescription('Registration, login, refresh and profile lookup for the Enterprise Trading Platform')
    .setVersion('1.0.0')
    .addBearerAuth()
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('docs', app, document, { jsonDocumentUrl: 'docs/json' });

  await app.listen(process.env.PORT ?? 3000);
}
await bootstrap();