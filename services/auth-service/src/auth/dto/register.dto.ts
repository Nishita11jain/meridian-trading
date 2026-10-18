import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import {
  IsArray,
  IsInt,
  IsOptional,
  IsString,
  Length,
  Matches,
  Min,
} from 'class-validator';
import { Type } from 'class-transformer';

export class RegisterRequest {
  @ApiProperty({
    description: 'Unique username, 3-64 characters; letters, digits, dot, underscore and hyphen only (pattern ^[a-zA-Z0-9._-]+$), no spaces.',
  })
  @IsString()
  @Length(3, 64)
  // contracts/api-schemas/auth-api.yaml fixes this pattern for RegisterRequest.username:
  // letters, digits, dot, underscore and hyphen only -- no spaces.
  @Matches(/^[a-zA-Z0-9._-]+$/)
  username!: string;

  @ApiProperty({ description: 'Password, 12-128 characters.' })
  @IsString()
  @Length(12, 128)
  password!: string;

  @ApiProperty({ description: 'Integer id (minimum 1) of the existing, usable account to link this user to.' })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  accountId!: number;

  @ApiPropertyOptional({
    description: 'Optional array of role strings. Ignored by the server: every self-registration is created as CUSTOMER.',
    type: [String],
  })
  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  roles?: string[];
}
