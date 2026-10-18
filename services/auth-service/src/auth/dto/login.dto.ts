import { ApiProperty } from '@nestjs/swagger';
import { IsString, Length } from 'class-validator';

export class LoginRequest {
  @ApiProperty({ description: 'Username of the account logging in, 1-64 characters.' })
  @IsString()
  @Length(1, 64)
  username!: string;

  @ApiProperty({ description: 'Account password, 1-128 characters.' })
  @IsString()
  @Length(1, 128)
  password!: string;
}
