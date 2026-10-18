import { ApiProperty } from '@nestjs/swagger';
import { IsString } from 'class-validator';

export class RefreshRequest {
  @ApiProperty({ description: 'Refresh token previously issued by login or refresh; it is rotated on use. Must be a string.' })
  @IsString()
  refreshToken!: string;
}
