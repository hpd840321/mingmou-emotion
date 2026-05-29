import client from './client'

export interface LoginResponse {
  token: string
  user: {
    username: string
    name: string
    role: string
    gradeId?: number
    classId?: number
  }
}

export interface UserInfo {
  username: string
  name: string
  role: string
  gradeId?: number
  classId?: number
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res: any = await client.post('/auth/login', { username, password })
  return res as LoginResponse
}

export async function fetchMe(): Promise<UserInfo> {
  const res: any = await client.get('/auth/me')
  return res as UserInfo
}
