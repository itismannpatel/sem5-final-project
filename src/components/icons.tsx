import type { SVGProps } from 'react'
import {
  ArrowDown,
  ArrowLeftRight,
  ArrowRight,
  ArrowUpRight,
  Award,
  BadgeCheck,
  BookOpen,
  Calendar,
  CalendarCheck,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Clock,
  Compass,
  CreditCard,
  Crown,
  Download,
  Eye,
  ExternalLink,
  Filter,
  Gem,
  GraduationCap,
  Handshake,
  Heart,
  Home,
  Inbox,
  Info,
  Lightbulb,
  Lock,
  LogOut,
  Mail,
  MapPin,
  Menu,
  MessageSquare,
  Pencil,
  Plus,
  Quote,
  RefreshCw,
  Repeat,
  Search,
  Send,
  Settings,
  Shield,
  Sparkles,
  Star,
  Target,
  TrendingUp,
  User,
  Users,
  Wallet,
  X,
  Zap,
  type LucideIcon,
} from 'lucide-react'

/**
 * Every icon in Twofold comes from Lucide (one maintained set, one 24px grid).
 * The `Icon` wrapper keeps a name-based API so call sites stay declarative
 * and swapping a glyph later is a one-line change in the map below.
 */

export type IconName =
  | 'search'
  | 'compass'
  | 'sparkles'
  | 'calendar'
  | 'calendarCheck'
  | 'message'
  | 'user'
  | 'users'
  | 'logout'
  | 'settings'
  | 'home'
  | 'chevronDown'
  | 'chevronLeft'
  | 'chevronRight'
  | 'arrowRight'
  | 'arrowUpRight'
  | 'arrowDown'
  | 'swap'
  | 'repeat'
  | 'plus'
  | 'check'
  | 'checkCircle'
  | 'x'
  | 'clock'
  | 'send'
  | 'menu'
  | 'zap'
  | 'shield'
  | 'download'
  | 'edit'
  | 'award'
  | 'book'
  | 'target'
  | 'eye'
  | 'refresh'
  | 'mapPin'
  | 'mail'
  | 'star'
  | 'crown'
  | 'heart'
  | 'trendingUp'
  | 'filter'
  | 'handshake'
  | 'lightbulb'
  | 'graduationCap'
  | 'badgeCheck'
  | 'info'
  | 'externalLink'
  | 'inbox'
  | 'creditCard'
  | 'wallet'
  | 'lock'
  | 'gem'
  | 'quote'

const ICONS: Record<IconName, LucideIcon> = {
  search: Search,
  compass: Compass,
  sparkles: Sparkles,
  calendar: Calendar,
  calendarCheck: CalendarCheck,
  message: MessageSquare,
  user: User,
  users: Users,
  logout: LogOut,
  settings: Settings,
  home: Home,
  chevronDown: ChevronDown,
  chevronLeft: ChevronLeft,
  chevronRight: ChevronRight,
  arrowRight: ArrowRight,
  arrowUpRight: ArrowUpRight,
  arrowDown: ArrowDown,
  swap: ArrowLeftRight,
  repeat: Repeat,
  plus: Plus,
  check: Check,
  checkCircle: CheckCircle2,
  x: X,
  clock: Clock,
  send: Send,
  menu: Menu,
  zap: Zap,
  shield: Shield,
  download: Download,
  edit: Pencil,
  award: Award,
  book: BookOpen,
  target: Target,
  eye: Eye,
  refresh: RefreshCw,
  mapPin: MapPin,
  mail: Mail,
  star: Star,
  crown: Crown,
  heart: Heart,
  trendingUp: TrendingUp,
  filter: Filter,
  handshake: Handshake,
  lightbulb: Lightbulb,
  graduationCap: GraduationCap,
  badgeCheck: BadgeCheck,
  info: Info,
  externalLink: ExternalLink,
  inbox: Inbox,
  creditCard: CreditCard,
  wallet: Wallet,
  lock: Lock,
  gem: Gem,
  quote: Quote,
}

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name'> {
  name: IconName
  size?: number
}

export function Icon({ name, size = 18, strokeWidth = 1.8, ...rest }: IconProps) {
  const Glyph = ICONS[name]
  return (
    <Glyph
      size={size}
      strokeWidth={strokeWidth}
      aria-hidden="true"
      focusable="false"
      {...rest}
    />
  )
}

export function StarIcon({
  size = 14,
  filled = true,
  ...rest
}: { size?: number; filled?: boolean } & SVGProps<SVGSVGElement>) {
  return (
    <Star
      size={size}
      fill={filled ? 'currentColor' : 'none'}
      strokeWidth={filled ? 0 : 1.6}
      aria-hidden="true"
      {...rest}
    />
  )
}
