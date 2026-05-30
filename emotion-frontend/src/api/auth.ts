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
  const res = await client.post<any>('/auth/login', { username, password })
  return res.data as LoginResponse
}

export async function fetchMe(): Promise<UserInfo> {
  const res = await client.get<any>('/auth/me')
  return res.data as UserInfo
}
